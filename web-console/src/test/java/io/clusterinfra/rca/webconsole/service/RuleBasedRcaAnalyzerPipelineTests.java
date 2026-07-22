package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.clusterinfra.rca.webconsole.analysis.pipeline.EvidencePreprocessingStage;
import io.clusterinfra.rca.webconsole.analysis.pipeline.LlmEnrichmentStage;
import io.clusterinfra.rca.webconsole.analysis.pipeline.RcaAnalysisPipelineContext.EnrichedAnalysis;
import io.clusterinfra.rca.webconsole.analysis.pipeline.RcaAnalysisPipelineContext.PreprocessedEvidence;
import io.clusterinfra.rca.webconsole.analysis.pipeline.RcaAnalysisPipelineContext.RuleAnalysis;
import io.clusterinfra.rca.webconsole.analysis.pipeline.ReportAssemblyStage;
import io.clusterinfra.rca.webconsole.analysis.pipeline.RuleAnalysisStage;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RuleBasedRcaAnalyzerPipelineTests {
    @Mock
    private EvidencePreprocessingStage preprocessing;

    @Mock
    private RuleAnalysisStage ruleAnalysis;

    @Mock
    private LlmEnrichmentStage llmEnrichment;

    @Mock
    private ReportAssemblyStage reportAssembly;

    private RuleBasedRcaAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new RuleBasedRcaAnalyzer(preprocessing, ruleAnalysis, llmEnrichment, reportAssembly);
    }

    @Test
    void executesExplicitStagesInOrder() {
        EvidenceBundle evidence = evidence();
        PreprocessedEvidence preprocessed = new PreprocessedEvidence(
            evidence, List.of(), Map.of(), Map.of(), Map.of()
        );
        RuleAnalysis rules = new RuleAnalysis(preprocessed, List.of(), List.of(), Map.of(), Map.of());
        EnrichedAnalysis enriched = new EnrichedAnalysis(
            preprocessed, List.of(), List.of(), Map.of(), Map.of(), Map.of()
        );
        RcaReport report = mock(RcaReport.class);
        when(preprocessing.process(evidence)).thenReturn(preprocessed);
        when(ruleAnalysis.process(preprocessed)).thenReturn(rules);
        when(llmEnrichment.process(rules)).thenReturn(enriched);
        when(reportAssembly.assemble("report-1", enriched)).thenReturn(report);

        assertThat(analyzer.analyze("report-1", evidence)).isSameAs(report);

        InOrder order = inOrder(preprocessing, ruleAnalysis, llmEnrichment, reportAssembly);
        order.verify(preprocessing).process(evidence);
        order.verify(ruleAnalysis).process(preprocessed);
        order.verify(llmEnrichment).process(rules);
        order.verify(reportAssembly).assemble("report-1", enriched);
    }

    @Test
    void delegatesSignalQueriesToPreprocessingStage() {
        Map<String, Object> collectors = Map.of("disk", Map.of("usage_percent", 95));
        when(preprocessing.hasActionableSignals(collectors)).thenReturn(true);
        when(preprocessing.hasActionableSignals("cluster-1", collectors)).thenReturn(true);
        when(preprocessing.timelineSignals(collectors)).thenReturn(List.of(Map.of("name", "disk_usage_critical")));

        assertThat(analyzer.hasActionableSignals(collectors)).isTrue();
        assertThat(analyzer.hasActionableSignals("cluster-1", collectors)).isTrue();
        assertThat(analyzer.deriveTimelineSignals(collectors)).hasSize(1);

        verify(preprocessing).hasActionableSignals(collectors);
        verify(preprocessing).hasActionableSignals("cluster-1", collectors);
        verify(preprocessing).timelineSignals(collectors);
    }

    private EvidenceBundle evidence() {
        return new EvidenceBundle(
            "evidence-1",
            "cluster-1",
            "node-1",
            "DiskPressure",
            Instant.parse("2026-07-22T03:00:00Z"),
            Map.of()
        );
    }
}
