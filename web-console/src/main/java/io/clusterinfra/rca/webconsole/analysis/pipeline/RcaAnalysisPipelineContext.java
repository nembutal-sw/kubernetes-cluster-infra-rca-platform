package io.clusterinfra.rca.webconsole.analysis.pipeline;

import io.clusterinfra.rca.webconsole.analysis.Signal;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RecommendedAction;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RcaAnalysisPipelineContext {
    private RcaAnalysisPipelineContext() {
    }

    public record PreprocessedEvidence(
        EvidenceBundle evidence,
        List<Signal> signals,
        Map<String, Object> evidenceQuality,
        Map<String, Object> evidenceContract,
        Map<String, Object> sanitizedCollectors
    ) {
        public PreprocessedEvidence {
            signals = List.copyOf(signals);
            evidenceQuality = immutableMap(evidenceQuality);
            evidenceContract = immutableMap(evidenceContract);
            sanitizedCollectors = immutableMap(sanitizedCollectors);
        }
    }

    public record RuleAnalysis(
        PreprocessedEvidence preprocessed,
        List<RootCauseCandidate> candidates,
        List<RecommendedAction> actions,
        Map<String, Object> qualityGate,
        Map<String, Object> llmPayload
    ) {
        public RuleAnalysis {
            candidates = List.copyOf(candidates);
            actions = List.copyOf(actions);
            qualityGate = immutableMap(qualityGate);
            llmPayload = immutableMap(llmPayload);
        }
    }

    public record EnrichedAnalysis(
        PreprocessedEvidence preprocessed,
        List<RootCauseCandidate> candidates,
        List<RecommendedAction> actions,
        Map<String, Object> qualityGate,
        Map<String, Object> llmPayload,
        Map<String, Object> llmAnalysis
    ) {
        public EnrichedAnalysis {
            candidates = List.copyOf(candidates);
            actions = List.copyOf(actions);
            qualityGate = immutableMap(qualityGate);
            llmPayload = immutableMap(llmPayload);
            llmAnalysis = immutableMap(llmAnalysis);
        }
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value == null ? Map.of() : value));
    }
}
