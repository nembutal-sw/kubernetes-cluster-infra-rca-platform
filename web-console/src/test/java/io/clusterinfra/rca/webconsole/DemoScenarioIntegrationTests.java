package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.DemoScenarioRunRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.persistence.AnalysisTaskRepository;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import io.clusterinfra.rca.webconsole.service.DemoScenarioService;
import io.clusterinfra.rca.webconsole.service.EvidenceBundleExportService;
import io.clusterinfra.rca.webconsole.service.RuleBasedRcaAnalyzer;
import io.clusterinfra.rca.webconsole.service.RcaAnalysisWorker;
import java.io.ByteArrayInputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:demo-scenario-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.model.chat=none",
    "rca.llm.enabled=false",
    "rca.demo.enabled=true",
    "rca.export.signature-secret=test-bundle-signing-secret",
    "rca.export.signature-key-id=test-key-1",
    "rca.pipeline.initial-delay-ms=600000"
})
class DemoScenarioIntegrationTests {
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DemoScenarioService demos;

    @Autowired
    private RcaAnalysisWorker worker;

    @Autowired
    private AnalysisTaskRepository tasks;

    @Autowired
    private ReportRepository reports;

    @Autowired
    private EvidenceRepository evidence;

    @Autowired
    private RuleBasedRcaAnalyzer analyzer;

    @Autowired
    private EvidenceBundleExportService exports;

    @Test
    void demoRunsThroughPipelineAndExportsEvidenceBundle() throws Exception {
        var queued = demos.run(
            "disk-pressure",
            new DemoScenarioRunRequest(true, null, "demo-worker-01"),
            null
        );

        assertThat(queued.analysisTask().source()).isEqualTo("demo");
        assertThat(worker.processAvailableTasks()).isEqualTo(1);
        var completed = tasks.find(queued.analysisTask().taskId()).orElseThrow();
        assertThat(completed.status()).isEqualTo(AnalysisTaskStatus.completed);

        var report = reports.findReport(completed.reportId()).orElseThrow();
        assertThat(report.trigger()).containsEntry("source", "demo").containsEntry("demo", true);
        assertThat(report.rootCauseCandidates().getFirst().confidenceScore()).isGreaterThanOrEqualTo(70);
        assertThat(report.rootCauseCandidates().getFirst().evidencePaths()).isNotEmpty();
        assertThat(report.scope().get("affected_pods").toString()).contains("payments/payment-api-7d9f9c");

        var bundle = exports.exportReport(report.reportId());
        List<String> entries = new ArrayList<>();
        String markdown = "";
        String manifest = "";
        try (ZipInputStream zip = new ZipInputStream(
            new ByteArrayInputStream(bundle.content()),
            StandardCharsets.UTF_8
        )) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
                if ("rca-report.md".equals(entry.getName())) {
                    markdown = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
                if ("manifest.json".equals(entry.getName())) {
                    manifest = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertThat(entries)
            .contains("summary.json", "signals.json", "timeline.json", "rca-report.md", "manifest.json")
            .anyMatch(name -> name.startsWith("evidence/"));
        assertThat(markdown)
            .contains("DiskPressure")
            .contains("## Derived Rule Signals")
            .contains("disk_usage_critical")
            .contains("automation_allowed=");
        assertThat(manifest)
            .contains(report.reportId())
            .contains("\"hash_algorithm\" : \"SHA-256\"")
            .contains("\"path\" : \"summary.json\"")
            .contains("\"path\" : \"rca-report.md\"")
            .contains("\"sha256\"");
        JsonNode manifestJson = objectMapper.readTree(manifest);
        JsonNode signature = manifestJson.path("signature");
        assertThat(signature.path("enabled").asBoolean()).isTrue();
        assertThat(signature.path("algorithm").asText()).isEqualTo("HMAC-SHA256");
        assertThat(signature.path("key_id").asText()).isEqualTo("test-key-1");
        assertThat(signature.path("canonicalization").asText()).isEqualTo("bundle-manifest-v1");
        assertThat(signature.path("value").asText())
            .isEqualTo(hmacSha256("test-bundle-signing-secret", canonicalManifest(manifestJson)));

        var summary = exports.reportManifest(report.reportId());
        assertThat(summary.filename()).isEqualTo("rca-evidence-bundle-" + report.reportId() + ".zip");
        assertThat(summary.reportId()).isEqualTo(report.reportId());
        assertThat(summary.evidenceCount()).isGreaterThanOrEqualTo(1);
        assertThat(summary.entryCount()).isGreaterThanOrEqualTo(5);
        assertThat(summary.entries()).extracting("path")
            .contains("summary.json", "signals.json", "timeline.json", "rca-report.md");
        assertThat(summary.signatureEnabled()).isTrue();
        assertThat(summary.signatureKeyId()).isEqualTo("test-key-1");
        assertThat(summary.verificationCommand())
            .contains("verify_evidence_bundle.py")
            .contains("--require-signature")
            .doesNotContain("test-bundle-signing-secret");
    }

    @Test
    void allDemoScenariosProduceSignalsCandidatesAndSafeActionPolicies() {
        for (var scenario : demos.scenarios()) {
            var queued = demos.run(
                scenario.key(),
                new DemoScenarioRunRequest(true, null, "demo-worker-01"),
                null
            );

            var evidenceBundle = evidence.find(queued.analysisTask().evidenceId()).orElseThrow();
            var report = analyzer.analyze("report-" + scenario.key(), evidenceBundle);
            processUntilClosed(queued.analysisTask().taskId());
            var completed = tasks.find(queued.analysisTask().taskId()).orElseThrow();
            assertThat(completed.status()).as(scenario.key()).isEqualTo(AnalysisTaskStatus.completed);

            assertThat(signalNames(report)).as(scenario.key())
                .isNotEmpty()
                .containsAnyElementsOf(expectedSignals(scenario.key()));
            assertThat(report.rootCauseCandidates()).as(scenario.key()).isNotEmpty();
            assertThat(report.rootCauseCandidates().getFirst().confidenceScore()).as(scenario.key())
                .isGreaterThanOrEqualTo(50);
            assertThat(report.rootCauseCandidates().getFirst().evidencePaths()).as(scenario.key()).isNotEmpty();
            assertThat(report.recommendedActions()).as(scenario.key()).isNotEmpty();
            assertThat(report.recommendedActions()).as(scenario.key()).allSatisfy(action -> {
                if ("llm".equals(action.source())) {
                    assertThat(action.automationAllowed()).isFalse();
                }
                if (Set.of(
                    "restart_kubelet",
                    "restart_containerd",
                    "restart_container_runtime",
                    "cleanup_disk",
                    "cordon_node",
                    "reboot_node",
                    "open_gitops_pr"
                ).contains(action.actionKey())) {
                    assertThat(action.automationAllowed()).isFalse();
                    if (action.executionPlan() != null) {
                        assertThat(action.executionPlan().executable()).isFalse();
                    }
                }
            });
        }
    }

    private void processUntilClosed(String taskId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            worker.processAvailableTasks();
            var task = tasks.find(taskId).orElseThrow();
            if (task.status() == AnalysisTaskStatus.completed
                || task.status() == AnalysisTaskStatus.skipped
                || task.status() == AnalysisTaskStatus.dead_letter) {
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> signalNames(RcaReport report) {
        return report.evidence().stream()
            .filter(section -> "derived_signals".equals(section.get("type")))
            .findFirst()
            .map(section -> (List<Map<String, Object>>) section.get("signals"))
            .orElse(List.of())
            .stream()
            .map(signal -> String.valueOf(signal.get("signal")))
            .toList();
    }

    private List<String> expectedSignals(String scenarioKey) {
        return switch (scenarioKey) {
            case "node-not-ready" -> List.of("node_not_ready");
            case "disk-pressure" -> List.of("disk_usage_critical", "disk_io_latency_high");
            case "inode-exhaustion" -> List.of("inode_usage_critical");
            case "memory-pressure" -> List.of("memory_pressure_critical", "kernel_oom_detected");
            case "pid-pressure" -> List.of("pid_usage_high");
            case "kubelet-failure" -> List.of("kubelet_unit_unhealthy", "systemd_failed_units");
            case "runtime-failure" -> List.of("containerd_unit_unhealthy", "container_runtime_unit_unhealthy");
            case "coredns-latency" -> List.of("dns_latency_high");
            case "cni-mtu-mismatch" -> List.of("cni_mtu_values_inconsistent");
            case "conntrack-exhaustion" -> List.of("conntrack_near_limit");
            case "etcd-latency" -> List.of("etcd_latency_high", "disk_io_latency_high");
            case "api-server-latency" -> List.of("api_server_latency_high");
            case "kernel-io-error" -> List.of("kernel_io_error", "disk_io_latency_high");
            case "network-link-flap" -> List.of("nic_link_flap");
            case "systemd-restart-loop" -> List.of("systemd_failed_units", "kubelet_unit_unhealthy");
            default -> List.of();
        };
    }

    private String canonicalManifest(JsonNode manifest) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("schema_version=").append(manifest.path("schema_version").asText()).append('\n');
        canonical.append("generated_at=").append(manifest.path("generated_at").asText()).append('\n');
        canonical.append("report_id=").append(manifest.path("report_id").asText()).append('\n');
        canonical.append("incident_id=").append(manifest.path("incident_id").asText()).append('\n');
        canonical.append("cluster_id=").append(manifest.path("cluster_id").asText()).append('\n');
        canonical.append("node_name=").append(manifest.path("node_name").asText()).append('\n');
        canonical.append("evidence_count=").append(manifest.path("evidence_count").asInt()).append('\n');
        canonical.append("hash_algorithm=").append(manifest.path("hash_algorithm").asText()).append('\n');

        List<JsonNode> entries = new ArrayList<>();
        manifest.path("entries").forEach(entries::add);
        entries.stream()
            .sorted(Comparator.comparing(entry -> entry.path("path").asText()))
            .forEach(entry -> canonical
                .append("entry:")
                .append(entry.path("path").asText())
                .append('=')
                .append(entry.path("sha256").asText())
                .append('\n'));
        return canonical.toString();
    }

    private String hmacSha256(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
