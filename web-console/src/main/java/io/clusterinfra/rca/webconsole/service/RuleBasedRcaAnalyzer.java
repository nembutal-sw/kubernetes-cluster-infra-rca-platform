package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.analysis.pipeline.EvidencePreprocessingStage;
import io.clusterinfra.rca.webconsole.analysis.pipeline.LlmEnrichmentStage;
import io.clusterinfra.rca.webconsole.analysis.pipeline.ReportAssemblyStage;
import io.clusterinfra.rca.webconsole.analysis.pipeline.RuleAnalysisStage;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RuleBasedRcaAnalyzer {
    private final EvidencePreprocessingStage preprocessing;
    private final RuleAnalysisStage ruleAnalysis;
    private final LlmEnrichmentStage llmEnrichment;
    private final ReportAssemblyStage reportAssembly;

    public RuleBasedRcaAnalyzer(
        EvidencePreprocessingStage preprocessing,
        RuleAnalysisStage ruleAnalysis,
        LlmEnrichmentStage llmEnrichment,
        ReportAssemblyStage reportAssembly
    ) {
        this.preprocessing = preprocessing;
        this.ruleAnalysis = ruleAnalysis;
        this.llmEnrichment = llmEnrichment;
        this.reportAssembly = reportAssembly;
    }

    public RcaReport analyze(String reportId, EvidenceBundle evidence) {
        return reportAssembly.assemble(
            reportId,
            llmEnrichment.process(ruleAnalysis.process(preprocessing.process(evidence)))
        );
    }

    public boolean hasActionableSignals(Map<String, Object> collectors) {
        return preprocessing.hasActionableSignals(collectors);
    }

    public boolean hasActionableSignals(String clusterId, Map<String, Object> collectors) {
        return preprocessing.hasActionableSignals(clusterId, collectors);
    }

    public List<Map<String, Object>> deriveTimelineSignals(Map<String, Object> collectors) {
        return preprocessing.timelineSignals(collectors);
    }
}
