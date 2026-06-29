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
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("schema_version", "1.0");
            summary.put("report", safeMap(report));
            summary.put("incident", safeMap(incident));
            summary.put("evidence_count", bundles.size());
            addJson(zip, "summary.json", summary, rawBytes, maxBytes);
            for (EvidenceBundle bundle : bundles) {
                addJson(
                    zip,
                    "evidence/" + safeName(bundle.evidenceId()) + ".json",
                    safeMap(bundle),
                    rawBytes,
                    maxBytes
                );
            }
            addJson(zip, "signals.json", report.evidence(), rawBytes, maxBytes);
            addJson(zip, "timeline.json", safeMap(timeline), rawBytes, maxBytes);
            addEntry(
                zip,
                "rca-report.md",
                SensitiveDataRedactor.redactText(markdown(report, incident)).getBytes(StandardCharsets.UTF_8),
                rawBytes,
                maxBytes
            );
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
        long maxBytes
    ) throws IOException {
        Object redacted = value instanceof Map<?, ?> map
            ? SensitiveDataRedactor.redactMap(stringKeyMap(map))
            : SensitiveDataRedactor.redactValue(value);
        addEntry(
            zip,
            name,
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(redacted),
            rawBytes,
            maxBytes
        );
    }

    private void addEntry(
        ZipOutputStream zip,
        String name,
        byte[] content,
        long[] rawBytes,
        long maxBytes
    ) throws IOException {
        rawBytes[0] += content.length;
        if (rawBytes[0] > maxBytes) {
            throw new ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "evidence bundle exceeds the configured export size limit"
            );
        }
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
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
