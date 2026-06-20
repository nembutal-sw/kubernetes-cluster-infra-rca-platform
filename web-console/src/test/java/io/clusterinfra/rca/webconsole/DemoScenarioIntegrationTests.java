package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AnalysisTaskStatus;
import io.clusterinfra.rca.webconsole.domain.RcaModels.DemoScenarioRunRequest;
import io.clusterinfra.rca.webconsole.persistence.AnalysisTaskRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import io.clusterinfra.rca.webconsole.service.DemoScenarioService;
import io.clusterinfra.rca.webconsole.service.EvidenceBundleExportService;
import io.clusterinfra.rca.webconsole.service.RcaAnalysisWorker;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:demo-scenario-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.model.chat=none",
    "rca.llm.enabled=false",
    "rca.demo.enabled=true",
    "rca.pipeline.initial-delay-ms=600000"
})
class DemoScenarioIntegrationTests {
    @Autowired
    private DemoScenarioService demos;

    @Autowired
    private RcaAnalysisWorker worker;

    @Autowired
    private AnalysisTaskRepository tasks;

    @Autowired
    private ReportRepository reports;

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
            }
        }
        assertThat(entries)
            .contains("summary.json", "signals.json", "timeline.json", "rca-report.md")
            .anyMatch(name -> name.startsWith("evidence/"));
        assertThat(markdown).contains("DiskPressure").contains("automation_allowed=");
    }
}
