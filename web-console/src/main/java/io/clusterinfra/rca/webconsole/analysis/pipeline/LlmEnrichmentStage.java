package io.clusterinfra.rca.webconsole.analysis.pipeline;

import io.clusterinfra.rca.webconsole.analysis.EvidenceQualityAnalyzer;
import io.clusterinfra.rca.webconsole.analysis.pipeline.RcaAnalysisPipelineContext.EnrichedAnalysis;
import io.clusterinfra.rca.webconsole.analysis.pipeline.RcaAnalysisPipelineContext.RuleAnalysis;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RecommendedAction;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import io.clusterinfra.rca.webconsole.service.LlmAnalysisService;
import io.clusterinfra.rca.webconsole.service.PolicyEngine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class LlmEnrichmentStage {
    private final LlmAnalysisService llm;
    private final PolicyEngine policyEngine;
    private final EvidenceQualityAnalyzer evidenceQualityAnalyzer;
    private final RcaQualityGateEvaluator qualityGateEvaluator;

    public LlmEnrichmentStage(
        LlmAnalysisService llm,
        PolicyEngine policyEngine,
        EvidenceQualityAnalyzer evidenceQualityAnalyzer,
        RcaQualityGateEvaluator qualityGateEvaluator
    ) {
        this.llm = llm;
        this.policyEngine = policyEngine;
        this.evidenceQualityAnalyzer = evidenceQualityAnalyzer;
        this.qualityGateEvaluator = qualityGateEvaluator;
    }

    public EnrichedAnalysis process(RuleAnalysis ruleAnalysis) {
        Map<String, Object> llmAnalysis = llm.analyze(ruleAnalysis.llmPayload());
        List<RootCauseCandidate> candidates = mergeLlmCandidates(
            ruleAnalysis.candidates(),
            llmAnalysis
        );
        candidates = applyEvidenceQualityPenalty(
            candidates,
            ruleAnalysis.preprocessed().evidenceQuality()
        );
        List<RecommendedAction> actions = mergeLlmActions(ruleAnalysis.actions(), llmAnalysis);
        Map<String, Object> qualityGate = qualityGateEvaluator.evaluate(
            ruleAnalysis.preprocessed().signals(),
            candidates,
            ruleAnalysis.preprocessed().evidenceQuality()
        );
        Map<String, Object> llmPayload = new LinkedHashMap<>(ruleAnalysis.llmPayload());
        llmPayload.put("final_quality_gate", qualityGate);
        return new EnrichedAnalysis(
            ruleAnalysis.preprocessed(),
            candidates,
            actions,
            qualityGate,
            llmPayload,
            llmAnalysis
        );
    }

    private List<RootCauseCandidate> mergeLlmCandidates(
        List<RootCauseCandidate> ruleCandidates,
        Map<String, Object> analysis
    ) {
        List<RootCauseCandidate> merged = new ArrayList<>(ruleCandidates);
        resultList(analysis, "root_cause_candidates").stream().limit(3).forEach(item -> {
            String cause = string(item.get("cause"));
            if (cause.isBlank()) {
                return;
            }
            List<String> evidencePaths = stringList(item.get("evidence_paths"));
            List<String> supportingEvidence = new ArrayList<>(stringList(item.get("supporting_evidence")));
            String scoreReason = string(item.get("score_reason"));
            if (!scoreReason.isBlank()) {
                supportingEvidence.add(scoreReason);
            }
            supportingEvidence.add("LLM diagnostic-only candidate; rule-based evidence remains the confidence source.");
            merged.add(new RootCauseCandidate(
                cause.startsWith("LLM diagnostic: ") ? cause : "LLM diagnostic: " + cause,
                Confidence.low,
                supportingEvidence,
                evidencePaths.isEmpty() ? 15 : 25,
                evidencePaths
            ));
        });
        return merged;
    }

    private List<RootCauseCandidate> applyEvidenceQualityPenalty(
        List<RootCauseCandidate> candidates,
        Map<String, Object> quality
    ) {
        int penalty = evidenceQualityAnalyzer.confidencePenalty(quality);
        if (penalty <= 0) {
            return candidates;
        }
        return candidates.stream().map(candidate -> new RootCauseCandidate(
            candidate.cause(),
            adjustConfidence(candidate.confidence(), penalty),
            withQualityNote(candidate.supportingEvidence(), quality),
            Math.max(0, candidate.confidenceScore() - penalty),
            candidate.evidencePaths()
        )).toList();
    }

    private List<String> withQualityNote(List<String> supportingEvidence, Map<String, Object> quality) {
        List<String> values = new ArrayList<>(supportingEvidence == null ? List.of() : supportingEvidence);
        Object notes = quality.get("notes");
        if (notes instanceof List<?> list && !list.isEmpty()) {
            values.add("Evidence quality: " + String.join("; ", list.stream().map(String::valueOf).toList()));
        }
        return values;
    }

    private Confidence adjustConfidence(Confidence confidence, int penalty) {
        if (penalty >= 40) {
            return Confidence.low;
        }
        if (penalty >= 20 && confidence == Confidence.high) {
            return Confidence.medium;
        }
        if (penalty >= 20 && confidence == Confidence.medium) {
            return Confidence.low;
        }
        return confidence;
    }

    private List<RecommendedAction> mergeLlmActions(
        List<RecommendedAction> ruleActions,
        Map<String, Object> analysis
    ) {
        List<RecommendedAction> merged = new ArrayList<>(ruleActions);
        resultList(analysis, "action_suggestions").stream().limit(5).forEach(item -> {
            String action = string(item.get("action"));
            String reason = string(item.get("reason"));
            if (action.isBlank() || reason.isBlank()) {
                return;
            }
            merged.add(policyEngine.classify(
                string(item.getOrDefault("action_key", "manual_investigation")),
                action,
                reason,
                "llm",
                Map.of()
            ));
        });
        return merged;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resultList(Map<String, Object> analysis, String key) {
        Object result = analysis.get("result");
        if (!(result instanceof Map<?, ?> resultMap)) {
            return List.of();
        }
        Object value = resultMap.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(Map.class::isInstance)
            .map(item -> (Map<String, Object>) item)
            .toList();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(this::string).filter(item -> !item.isBlank()).limit(10).toList();
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
