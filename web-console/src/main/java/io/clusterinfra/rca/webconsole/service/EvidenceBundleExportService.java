package io.clusterinfra.rca.webconsole.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Incident;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentTimeline;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import io.clusterinfra.rca.webconsole.persistence.IncidentRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import io.clusterinfra.rca.webconsole.security.SensitiveDataRedactor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EvidenceBundleExportService {
    private final ReportRepository reports;
    private final IncidentRepository incidents;
    private final EvidenceRepository evidence;
    private final IncidentTimelineService timelines;
    private final RcaConsoleProperties properties;
    private final ObjectMapper objectMapper;

    public EvidenceBundleExportService(
        ReportRepository reports,
        IncidentRepository incidents,
        EvidenceRepository evidence,
        IncidentTimelineService timelines,
        RcaConsoleProperties properties,
        ObjectMapper objectMapper
    ) {
        this.reports = reports;
        this.incidents = incidents;
        this.evidence = evidence;
        this.timelines = timelines;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ExportedBundle exportReport(String reportId) {
        RcaReport report = reports.findReport(reportId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "RCA report not found"));
        Incident incident = incidentFor(report);
        return createBundle(report, incident);
    }

    public ExportedBundle exportIncident(String incidentId) {
        Incident incident = incidents.find(incidentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "incident not found"));
        RcaReport report = reports.findReport(incident.latestReportId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "incident report not found"));
        return createBundle(report, incident);
    }

    private Incident incidentFor(RcaReport report) {
        if (report.incidentId() == null || report.incidentId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "report is not associated with an incident");
        }
        return incidents.find(report.incidentId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "incident not found"));
    }

    private ExportedBundle createBundle(RcaReport report, Incident incident) {
        List<EvidenceBundle> bundles = evidence.listForNodeWindow(
            incident.clusterId(),
            incident.nodeName(),
            incident.firstSeenAt().minus(10, ChronoUnit.MINUTES),
            incident.lastSeenAt().plus(10, ChronoUnit.MINUTES)
        );
        IncidentTimeline timeline = timelines.build(incident);
        long maxBytes = Math.max(1024, properties.getExport().getMaxBundleBytes());
        long[] rawBytes = {0};
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            Map<String, String> hashes = new LinkedHashMap<>();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("schema_version", "1.0");
            summary.put("report", safeMap(report));
            summary.put("incident", safeMap(incident));
            summary.put("evidence_count", bundles.size());
            addJson(zip, "summary.json", summary, rawBytes, maxBytes, hashes);
            for (EvidenceBundle bundle : bundles) {
                addJson(
                    zip,
                    "evidence/" + safeName(bundle.evidenceId()) + ".json",
                    safeMap(bundle),
                    rawBytes,
                    maxBytes,
                    hashes
                );
            }
            addJson(zip, "signals.json", report.evidence(), rawBytes, maxBytes, hashes);
            addJson(zip, "timeline.json", safeMap(timeline), rawBytes, maxBytes, hashes);
            addEntry(
                zip,
                "rca-report.md",
                SensitiveDataRedactor.redactText(markdown(report, incident)).getBytes(StandardCharsets.UTF_8),
                rawBytes,
                maxBytes,
                hashes
            );
            addJson(zip, "manifest.json", manifest(report, incident, bundles.size(), hashes), rawBytes, maxBytes, null);
            zip.finish();
            return new ExportedBundle(
                "incident-" + safeName(incident.incidentId()) + ".zip",
                output.toByteArray(),
                bundles.size(),
                rawBytes[0]
            );
        } catch (IOException exception) {
            throw new IllegalStateException("evidence bundle export failed", exception);
        }
    }

    private void addJson(
        ZipOutputStream zip,
        String name,
        Object value,
        long[] rawBytes,
        long maxBytes,
        Map<String, String> hashes
    ) throws IOException {
        Object redacted = value instanceof Map<?, ?> map
            ? SensitiveDataRedactor.redactMap(stringKeyMap(map))
            : SensitiveDataRedactor.redactValue(value);
        addEntry(
            zip,
            name,
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(redacted),
            rawBytes,
            maxBytes,
            hashes
        );
    }

    private void addEntry(
        ZipOutputStream zip,
        String name,
        byte[] content,
        long[] rawBytes,
        long maxBytes,
        Map<String, String> hashes
    ) throws IOException {
        rawBytes[0] += content.length;
        if (rawBytes[0] > maxBytes) {
            throw new ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "evidence bundle exceeds the configured export size limit"
            );
        }
        if (hashes != null) {
            hashes.put(name, sha256(content));
        }
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private Map<String, Object> manifest(
        RcaReport report,
        Incident incident,
        int evidenceCount,
        Map<String, String> hashes
    ) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        String generatedAt = Instant.now().toString();
        manifest.put("schema_version", "1.0");
        manifest.put("generated_at", generatedAt);
        manifest.put("report_id", report.reportId());
        manifest.put("incident_id", incident.incidentId());
        manifest.put("cluster_id", incident.clusterId());
        manifest.put("node_name", incident.nodeName());
        manifest.put("evidence_count", evidenceCount);
        manifest.put("hash_algorithm", "SHA-256");
        manifest.put("entries", hashes.entrySet().stream()
            .map(entry -> Map.of(
                "path", entry.getKey(),
                "sha256", entry.getValue()
            ))
            .toList());
        manifest.put("signature", signature(report, incident, generatedAt, evidenceCount, hashes));
        return manifest;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
    }

    private Map<String, Object> signature(
        RcaReport report,
        Incident incident,
        String generatedAt,
        int evidenceCount,
        Map<String, String> hashes
    ) {
        Map<String, Object> signature = new LinkedHashMap<>();
        String secret = properties.getExport().getSignatureSecret();
        if (secret.isBlank()) {
            signature.put("enabled", false);
            signature.put("reason", "signature_secret_not_configured");
            return signature;
        }
        signature.put("enabled", true);
        signature.put("algorithm", "HMAC-SHA256");
        signature.put("key_id", properties.getExport().getSignatureKeyId());
        signature.put("canonicalization", "bundle-manifest-v1");
        signature.put(
            "value",
            hmacSha256(secret, canonicalManifest(report, incident, generatedAt, evidenceCount, hashes))
        );
        return signature;
    }

    private String canonicalManifest(
        RcaReport report,
        Incident incident,
        String generatedAt,
        int evidenceCount,
        Map<String, String> hashes
    ) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("schema_version=1.0\n");
        canonical.append("generated_at=").append(generatedAt).append('\n');
        canonical.append("report_id=").append(report.reportId()).append('\n');
        canonical.append("incident_id=").append(incident.incidentId()).append('\n');
        canonical.append("cluster_id=").append(incident.clusterId()).append('\n');
        canonical.append("node_name=").append(incident.nodeName()).append('\n');
        canonical.append("evidence_count=").append(evidenceCount).append('\n');
        canonical.append("hash_algorithm=SHA-256\n");
        hashes.entrySet().stream()
            .sorted(Comparator.comparing(Map.Entry::getKey))
            .forEach(entry -> canonical
                .append("entry:")
                .append(entry.getKey())
                .append('=')
                .append(entry.getValue())
                .append('\n'));
        return canonical.toString();
    }

    private String hmacSha256(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("HMAC-SHA256 signing is not available", exception);
        }
    }

    private Map<String, Object> safeMap(Object value) {
        Map<String, Object> converted = objectMapper.convertValue(
            value,
            new TypeReference<LinkedHashMap<String, Object>>() {
            }
        );
        return SensitiveDataRedactor.redactMap(converted);
    }

    private Map<String, Object> stringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String markdown(RcaReport report, Incident incident) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# RCA Report\n\n");
        markdown.append("- Report: ").append(report.reportId()).append('\n');
        markdown.append("- Incident: ").append(incident.incidentId()).append('\n');
        markdown.append("- Cluster: ").append(report.clusterId()).append('\n');
        markdown.append("- Node: ").append(incident.nodeName()).append('\n');
        markdown.append("- Symptom: ").append(report.summary().symptom()).append('\n');
        markdown.append("- Most likely cause: ").append(report.summary().mostLikelyCause()).append('\n');
        markdown.append("- Confidence: ").append(report.summary().confidence()).append("\n\n");
        markdown.append("## Root Cause Candidates\n\n");
        report.rootCauseCandidates().forEach(candidate -> markdown
            .append("- [").append(candidate.confidenceScore()).append("%] ")
            .append(candidate.cause()).append('\n'));
        markdown.append("\n## Derived Rule Signals\n\n");
        List<Map<String, Object>> signals = derivedSignals(report);
        if (signals.isEmpty()) {
            markdown.append("- No derived rule signals.\n");
        } else {
            signals.forEach(signal -> {
                markdown.append("- ").append(value(signal, "signal"))
                    .append(" (`").append(value(signal, "severity")).append("`, confidence=")
                    .append(value(signal, "confidence")).append(")");
                if (!"n/a".equals(value(signal, "component"))) {
                    markdown.append(" component=").append(value(signal, "component"));
                }
                markdown.append('\n');
                appendNested(markdown, "  - Interpretation: ", signal.get("interpretation"));
                appendNested(markdown, "  - Matched fields: ", signal.get("matched_fields"));
                appendNested(markdown, "  - Observed: ", signal.get("observed"));
                appendNested(markdown, "  - Threshold: ", signal.get("threshold"));
                appendNested(markdown, "  - Next step: ", signal.get("next_step"));
            });
        }
        markdown.append("\n## Recommended Actions\n\n");
        report.recommendedActions().forEach(action -> markdown
            .append("- ").append(action.action())
            .append(" (`").append(action.policy()).append("`, automation_allowed=")
            .append(action.automationAllowed()).append(")\n"));
        return markdown.toString();
    }

    private List<Map<String, Object>> derivedSignals(RcaReport report) {
        List<Map<String, Object>> sections = report.evidence() == null ? List.of() : report.evidence();
        return sections.stream()
            .filter(section -> "derived_signals".equals(section.get("type")))
            .findFirst()
            .map(section -> section.get("signals"))
            .filter(List.class::isInstance)
            .map(signals -> ((List<?>) signals).stream()
                .filter(Map.class::isInstance)
                .map(signal -> stringKeyMap((Map<?, ?>) signal))
                .toList())
            .orElse(List.of());
    }

    private void appendNested(StringBuilder markdown, String label, Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return;
        }
        markdown.append(label).append(value).append('\n');
    }

    private String value(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null || String.valueOf(value).isBlank() ? "n/a" : String.valueOf(value);
    }

    private String safeName(String value) {
        return value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    public record ExportedBundle(
        String filename,
        byte[] content,
        int evidenceCount,
        long rawBytes
    ) {
    }
}
