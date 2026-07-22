package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.analysis.ConfidenceScorer;
import io.clusterinfra.rca.webconsole.analysis.CollectorEvidenceAdapter;
import io.clusterinfra.rca.webconsole.analysis.EvidenceQualityAnalyzer;
import io.clusterinfra.rca.webconsole.analysis.ImpactScopeAnalyzer;
import io.clusterinfra.rca.webconsole.analysis.RootCauseCandidateBuilder;
import io.clusterinfra.rca.webconsole.analysis.SignalDetectionEngine;
import io.clusterinfra.rca.webconsole.analysis.detector.DiskPressureDetector;
import io.clusterinfra.rca.webconsole.analysis.pipeline.EvidencePreprocessingStage;
import io.clusterinfra.rca.webconsole.analysis.pipeline.LlmEnrichmentStage;
import io.clusterinfra.rca.webconsole.analysis.pipeline.RcaQualityGateEvaluator;
import io.clusterinfra.rca.webconsole.analysis.pipeline.ReportAssemblyStage;
import io.clusterinfra.rca.webconsole.analysis.pipeline.RuleAnalysisStage;
import io.clusterinfra.rca.webconsole.catalog.OperationalCatalogService;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaJobStatus;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.service.AgentHealthService;
import io.clusterinfra.rca.webconsole.service.LlmAnalysisService;
import io.clusterinfra.rca.webconsole.service.PolicyEngine;
import io.clusterinfra.rca.webconsole.service.RuleBasedRcaAnalyzer;
import io.clusterinfra.rca.webconsole.service.TopologyService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RuleBasedLlmFallbackTests {
    @Test
    @SuppressWarnings("unchecked")
    void llmFailureDoesNotBlockRuleBasedReportOrMergeUntrustedActions() {
        LlmAnalysisService llm = mock(LlmAnalysisService.class);
        when(llm.analyze(anyMap())).thenReturn(Map.of(
            "status", "failed",
            "provider", "openai",
            "prompt_version", "llm-rca-analyzer/v2",
            "attempts", 2,
            "error", "LlmResponseValidationException: schema invalid"
        ));
        RuleBasedRcaAnalyzer analyzer = analyzer(llm);

        var report = analyzer.analyze(
            "report-llm-fallback",
            new EvidenceBundle(
                "evidence-llm-fallback",
                "cluster-a",
                "worker-a",
                "DiskPressure",
                Instant.now(),
                Map.of("disk", Map.of("root_usage_percent", 96.0))
            )
        );

        assertThat(report.status()).isEqualTo(RcaJobStatus.completed);
        assertThat(report.summary().mostLikelyCause()).contains("Filesystem capacity");
        assertThat(report.rootCauseCandidates()).hasSize(1);
        assertThat(report.rootCauseCandidates().getFirst().evidencePaths())
            .containsExactly("disk.root_usage_percent");
        assertThat(report.recommendedActions())
            .extracting(action -> action.source())
            .doesNotContain("llm");

        Map<String, Object> llmSection = report.evidence().stream()
            .filter(section -> "llm_analysis".equals(section.get("type")))
            .findFirst()
            .map(section -> (Map<String, Object>) section.get("analysis"))
            .orElseThrow();
        assertThat(llmSection)
            .containsEntry("status", "failed")
            .containsEntry("prompt_version", "llm-rca-analyzer/v2");

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(llm).analyze(payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        List<Map<String, Object>> catalog = (List<Map<String, Object>>) payload.get("evidence_catalog");
        assertThat(catalog).hasSize(1);
        assertThat(catalog.getFirst())
            .containsEntry("signal", "disk_usage_critical")
            .containsEntry("component", "disk");
        assertThat(String.valueOf(catalog.getFirst().get("evidence_id")))
            .matches("ev-[a-f0-9]{16}");
        assertThat(payload.get("llm_evidence_policy").toString())
            .contains("supporting_evidence_ids")
            .contains(String.valueOf(catalog.getFirst().get("evidence_id")));
    }

    private RuleBasedRcaAnalyzer analyzer(LlmAnalysisService llm) {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        ConfidenceScorer confidenceScorer = new ConfidenceScorer();
        AgentRepository agents = mock(AgentRepository.class);
        when(agents.find(anyString(), anyString())).thenReturn(Optional.empty());
        TopologyService topology = mock(TopologyService.class);
        when(topology.enrichScope(anyString(), anyString(), anyMap()))
            .thenAnswer(invocation -> invocation.getArgument(2));

        PolicyEngine policyEngine = new PolicyEngine();
        EvidenceQualityAnalyzer evidenceQuality = new EvidenceQualityAnalyzer(
            agents,
            mock(AgentHealthService.class),
            properties
        );
        RcaQualityGateEvaluator qualityGate = new RcaQualityGateEvaluator(evidenceQuality);
        return new RuleBasedRcaAnalyzer(
            new EvidencePreprocessingStage(
                new CollectorEvidenceAdapter(objectMapper),
                new SignalDetectionEngine(List.of(new DiskPressureDetector()), properties, objectMapper),
                evidenceQuality
            ),
            new RuleAnalysisStage(
                policyEngine,
                new RootCauseCandidateBuilder(confidenceScorer),
                OperationalCatalogService.defaultService(),
                qualityGate
            ),
            new LlmEnrichmentStage(llm, policyEngine, evidenceQuality, qualityGate),
            new ReportAssemblyStage(
                confidenceScorer,
                evidenceQuality,
                new ImpactScopeAnalyzer(),
                topology
            )
        );
    }
}
