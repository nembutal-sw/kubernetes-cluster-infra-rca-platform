package io.clusterinfra.rca.webconsole.analysis.pipeline;

import io.clusterinfra.rca.webconsole.analysis.EvidenceQualityAnalyzer;
import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Confidence;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RcaQualityGateEvaluator {
    private final EvidenceQualityAnalyzer evidenceQualityAnalyzer;

    public RcaQualityGateEvaluator(EvidenceQualityAnalyzer evidenceQualityAnalyzer) {
        this.evidenceQualityAnalyzer = evidenceQualityAnalyzer;
    }

    public Map<String, Object> evaluate(
        List<Signal> signals,
        List<RootCauseCandidate> candidates,
        Map<String, Object> evidenceQuality
    ) {
        int signalCount = signals == null ? 0 : signals.size();
        long highConfidenceSignals = signals == null
            ? 0
            : signals.stream().filter(signal -> signal.confidence() == Confidence.high).count();
        int topCandidateScore = candidates == null || candidates.isEmpty()
            ? 0
            : candidates.getFirst().confidenceScore();
        int penalty = evidenceQualityAnalyzer.confidencePenalty(evidenceQuality);
        String evidenceQualityStatus = String.valueOf(
            evidenceQuality == null ? "unknown" : evidenceQuality.getOrDefault("status", "unknown")
        );

        List<String> reasons = new ArrayList<>();
        List<String> followUp = new ArrayList<>();
        if (signalCount == 0) {
            reasons.add("No rule-based signal crossed an RCA threshold.");
            followUp.add("Collect full node diagnostics for the incident window.");
        }
        if (topCandidateScore < 60) {
            reasons.add("Top root-cause candidate score is below the high-confidence gate.");
            followUp.add("Review matched evidence paths and collect missing subsystem evidence.");
        }
        if (penalty > 0) {
            reasons.add("Evidence quality reduced report confidence.");
            followUp.add("Refresh stale, failed, or degraded collector output before remediation.");
        }
        if (!"complete".equals(evidenceQualityStatus)) {
            reasons.add("Evidence set is " + evidenceQualityStatus + ".");
        }

        String status;
        if (signalCount == 0 || topCandidateScore < 25 || penalty >= 50) {
            status = "insufficient";
        } else if (topCandidateScore < 60 || penalty > 0 || !"complete".equals(evidenceQualityStatus)) {
            status = "limited";
        } else {
            status = "pass";
        }
        if (reasons.isEmpty()) {
            reasons.add("Rule-based evidence is sufficient for an initial RCA report.");
        }
        if (followUp.isEmpty()) {
            followUp.add("Proceed with read-only verification commands before remediation.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("rule_signal_count", signalCount);
        result.put("high_confidence_signal_count", highConfidenceSignals);
        result.put("top_candidate_score", topCandidateScore);
        result.put("confidence_penalty", penalty);
        result.put("evidence_quality_status", evidenceQualityStatus);
        result.put("rule_based_sufficient", !"insufficient".equals(status));
        result.put("additional_evidence_required", !"pass".equals(status));
        result.put("llm_diagnostic_allowed", true);
        result.put("llm_should_not_raise_confidence", !"pass".equals(status));
        result.put("reasons", reasons);
        result.put("follow_up", followUp);
        return result;
    }
}
