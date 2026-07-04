package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.analysis.IncidentCausalityRules;
import io.clusterinfra.rca.webconsole.analysis.IncidentCausalityRules.CausalRelation;
import io.clusterinfra.rca.webconsole.analysis.IncidentCausalityRules.SignalProfile;
import io.clusterinfra.rca.webconsole.analysis.EvidenceQualityAnalyzer;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Incident;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentTimeline;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RealtimeEvent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RootCauseCandidate;
import io.clusterinfra.rca.webconsole.domain.RcaModels.TimelineEdge;
import io.clusterinfra.rca.webconsole.domain.RcaModels.TimelineNode;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

@Service
public class IncidentTimelineService {
    private final EvidenceRepository evidence;
    private final RuleBasedRcaAnalyzer analyzer;
    private final ReportRepository reports;
    private final IncidentCausalityRules causality;
    private final EvidenceQualityAnalyzer evidenceQuality;

    public IncidentTimelineService(
        EvidenceRepository evidence,
        RuleBasedRcaAnalyzer analyzer,
        ReportRepository reports,
        IncidentCausalityRules causality,
        EvidenceQualityAnalyzer evidenceQuality
    ) {
        this.evidence = evidence;
        this.analyzer = analyzer;
        this.reports = reports;
        this.causality = causality;
        this.evidenceQuality = evidenceQuality;
    }

    public IncidentTimeline build(Incident incident) {
        Instant from = incident.firstSeenAt().minus(10, ChronoUnit.MINUTES);
        Instant to = incident.lastSeenAt().plus(10, ChronoUnit.MINUTES);
        List<TimelineNode> nodes = new ArrayList<>();
        Set<String> realtimeEvidence = new HashSet<>();
        AtomicInteger sequence = new AtomicInteger();
        RcaReport canonicalReport = incident.latestReportId() == null
            ? null
            : reports.findReport(incident.latestReportId()).orElse(null);
        SignalProfile incidentProfile = causality.profile(incident, canonicalReport);

        for (RealtimeEvent event : evidence.listRealtimeEvents(
            incident.clusterId(), incident.nodeName(), from, to
        )) {
            String family = causality.familyForEvent(event.component(), event.eventType());
            if (!causality.connected(family, incidentProfile.families())) {
                continue;
            }
            Map<String, Object> quality = evidence.find(event.evidenceId())
                .map(evidenceQuality::assess)
                .map(evidenceQuality::compact)
                .orElse(Map.of());
            realtimeEvidence.add(event.evidenceId());
            nodes.add(new TimelineNode(
                "timeline-" + sequence.incrementAndGet(),
                event.observedAt(),
                event.component(),
                event.eventType(),
                family,
                event.severity(),
                title(event.eventType()),
                detail(event.payload()),
                event.evidenceId(),
                false,
                "realtime_event",
                List.of("realtime." + event.component() + "." + event.eventType()),
                matchesRootCandidate(canonicalReport, event.eventType(), List.of()),
                rootCandidateScore(canonicalReport, event.eventType(), List.of()),
                quality
            ));
        }
        for (EvidenceBundle bundle : evidence.listForNodeWindow(
            incident.clusterId(), incident.nodeName(), from, to
        )) {
            if (realtimeEvidence.contains(bundle.evidenceId())) {
                continue;
            }
            Map<String, Object> quality = evidenceQuality.assess(bundle);
            Map<String, Object> compactQuality = evidenceQuality.compact(quality);
            List<Map<String, Object>> signals = analyzer.deriveTimelineSignals(bundle.collectors());
            if (signals.isEmpty()) {
                String family = causality.familyForEvent("kubernetes", bundle.alertName());
                if (!causality.connected(family, incidentProfile.families())) {
                    continue;
                }
                nodes.add(new TimelineNode(
                    "timeline-" + sequence.incrementAndGet(),
                    bundle.collectedAt(),
                    "kubernetes",
                    bundle.alertName(),
                    family,
                    "info",
                    bundle.alertName(),
                    "Evidence was collected for this incident.",
                    bundle.evidenceId(),
                    false,
                    "alert_evidence",
                    List.of("alert." + bundle.alertName()),
                    matchesRootCandidate(canonicalReport, bundle.alertName(), List.of()),
                    rootCandidateScore(canonicalReport, bundle.alertName(), List.of()),
                    compactQuality
                ));
                continue;
            }
            for (Map<String, Object> signal : signals) {
                String component = String.valueOf(signal.getOrDefault("component", "node"));
                String signalName = String.valueOf(signal.getOrDefault("signal", bundle.alertName()));
                String family = causality.familyForEvent(component, signalName);
                if (!causality.connected(family, incidentProfile.families())) {
                    continue;
                }
                List<String> evidencePaths = strings(signal.get("matched_fields"));
                nodes.add(new TimelineNode(
                    "timeline-" + sequence.incrementAndGet(),
                    bundle.collectedAt(),
                    component,
                    signalName,
                    family,
                    String.valueOf(signal.getOrDefault("severity", "warning")),
                    title(signalName),
                    String.valueOf(signal.getOrDefault("interpretation", "")),
                    bundle.evidenceId(),
                    false,
                    "derived_signal",
                    evidencePaths,
                    matchesRootCandidate(canonicalReport, signalName, evidencePaths),
                    rootCandidateScore(canonicalReport, signalName, evidencePaths),
                    compactQuality
                ));
            }
        }
        nodes.sort(Comparator.comparing(TimelineNode::timestamp).thenComparing(TimelineNode::id));
        Map<String, TimelineEdge> causalIncoming = causalEdges(nodes);
        int rootIndex = rootIndex(nodes, causalIncoming);
        String rootId = null;
        if (!nodes.isEmpty()) {
            TimelineNode root = nodes.get(rootIndex);
            TimelineNode markedRoot = new TimelineNode(
                root.id(), root.timestamp(), root.component(), root.eventType(), root.signalFamily(), root.severity(),
                root.title(), root.detail(), root.evidenceId(), true, root.evidenceType(), root.evidencePaths(),
                true, root.rootCauseScore(), root.evidenceQuality()
            );
            rootId = markedRoot.id();
            nodes.set(rootIndex, markedRoot);
            if (rootIndex > 0) {
                nodes.remove(rootIndex);
                nodes.add(0, markedRoot);
            }
        }
        List<TimelineEdge> edges = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            TimelineNode target = nodes.get(index);
            if (target.id().equals(rootId)) {
                continue;
            }
            TimelineEdge causalEdge = causalIncoming.get(target.id());
            if (causalEdge != null) {
                edges.add(causalEdge);
                continue;
            }
            TimelineNode source = nodes.get(Math.max(0, index - 1));
            if (!source.id().equals(target.id())) {
                edges.add(new TimelineEdge(
                    source.id(),
                    target.id(),
                    "observed next in the incident window",
                    "temporal_sequence",
                    0.35,
                    false,
                    "observed_temporal_sequence",
                    "observed_sequence",
                    "weak"
                ));
            }
        }
        return new IncidentTimeline(
            incident.incidentId(),
            from,
            to,
            List.copyOf(nodes),
            List.copyOf(edges),
            summary(nodes, edges, canonicalReport, rootId)
        );
    }

    private Map<String, TimelineEdge> causalEdges(List<TimelineNode> nodes) {
        Map<String, TimelineEdge> incoming = new LinkedHashMap<>();
        for (TimelineNode target : nodes) {
            EdgeCandidate best = null;
            for (TimelineNode source : nodes) {
                if (source.id().equals(target.id())) {
                    continue;
                }
                if (source.signalFamily().equals(target.signalFamily())
                    && !source.timestamp().isBefore(target.timestamp())) {
                    continue;
                }
                Optional<CausalRelation> relation = causality.relation(
                    source.signalFamily(),
                    target.signalFamily(),
                    source.eventType(),
                    target.eventType()
                );
                if (relation.isEmpty()) {
                    continue;
                }
                long distance = Math.abs(Duration.between(source.timestamp(), target.timestamp()).toMillis());
                EdgeCandidate candidate = new EdgeCandidate(source, target, relation.get(), distance);
                if (best == null
                    || candidate.relation().confidence() > best.relation().confidence()
                    || (candidate.relation().confidence() == best.relation().confidence()
                        && candidate.distanceMillis() < best.distanceMillis())) {
                    best = candidate;
                }
            }
            if (best != null) {
                incoming.put(target.id(), new TimelineEdge(
                    best.source().id(),
                    target.id(),
                    best.relation().relationship(),
                    best.relation().ruleId(),
                    best.relation().confidence(),
                    true,
                    "rule_based_causal_relation",
                    best.source().timestamp().isAfter(best.target().timestamp())
                        ? "retroactive_root_cause_promotion"
                        : "upstream_to_downstream",
                    strength(best.relation().confidence())
                ));
            }
        }
        return incoming;
    }

    private int rootIndex(List<TimelineNode> nodes, Map<String, TimelineEdge> causalIncoming) {
        if (nodes.isEmpty()) {
            return -1;
        }
        return java.util.stream.IntStream.range(0, nodes.size())
            .boxed()
            .filter(index -> !causalIncoming.containsKey(nodes.get(index).id()))
            .min(Comparator
                .comparingInt((Integer index) -> causality.rootRank(nodes.get(index).signalFamily()))
                .thenComparingInt(index -> severityRank(nodes.get(index).severity()))
                .thenComparing(index -> nodes.get(index).timestamp()))
            .orElseGet(() -> java.util.stream.IntStream.range(0, nodes.size())
                .boxed()
                .min(Comparator
                    .comparingInt((Integer index) -> causality.rootRank(nodes.get(index).signalFamily()))
                    .thenComparing(index -> nodes.get(index).timestamp()))
                .orElse(0));
    }

    private static String title(String value) {
        return value.replace('_', ' ').replace('-', ' ');
    }

    private static String detail(Map<String, Object> payload) {
        Object summary = payload.get("summary");
        return summary == null ? payload.toString() : String.valueOf(summary);
    }

    private static int severityRank(String severity) {
        return switch (severity == null ? "" : severity.toLowerCase()) {
            case "critical" -> 0;
            case "warning" -> 1;
            default -> 2;
        };
    }

    @SuppressWarnings("unchecked")
    private List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private boolean matchesRootCandidate(RcaReport report, String eventType, List<String> evidencePaths) {
        return rootCandidateScore(report, eventType, evidencePaths) != null;
    }

    private Integer rootCandidateScore(RcaReport report, String eventType, List<String> evidencePaths) {
        if (report == null || report.rootCauseCandidates().isEmpty()) {
            return null;
        }
        String normalizedEvent = normalize(eventType);
        for (RootCauseCandidate candidate : report.rootCauseCandidates()) {
            boolean pathMatch = !evidencePaths.isEmpty()
                && candidate.evidencePaths().stream().anyMatch(evidencePaths::contains);
            boolean causeMatch = !normalizedEvent.isBlank()
                && normalize(candidate.cause()).contains(normalizedEvent.replace("_", " "));
            if (pathMatch || causeMatch) {
                return candidate.confidenceScore();
            }
        }
        return null;
    }

    private Map<String, Object> summary(
        List<TimelineNode> nodes,
        List<TimelineEdge> edges,
        RcaReport canonicalReport,
        String rootId
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node_count", nodes.size());
        result.put("edge_count", edges.size());
        result.put("causal_edge_count", edges.stream().filter(TimelineEdge::inferred).count());
        result.put("temporal_edge_count", edges.stream().filter(edge -> !edge.inferred()).count());
        result.put("root_node_id", rootId);
        nodes.stream()
            .filter(TimelineNode::rootTrigger)
            .findFirst()
            .ifPresent(root -> {
                result.put("root_signal_family", root.signalFamily());
                result.put("root_event_type", root.eventType());
                result.put("root_title", root.title());
            });
        if (canonicalReport != null && !canonicalReport.rootCauseCandidates().isEmpty()) {
            RootCauseCandidate top = canonicalReport.rootCauseCandidates().getFirst();
            result.put("root_cause_candidate", top.cause());
            result.put("root_cause_score", top.confidenceScore());
        }
        long stale = nodes.stream()
            .filter(node -> Boolean.TRUE.equals(node.evidenceQuality().get("stale"))
                || "stale".equals(String.valueOf(node.evidenceQuality().get("status"))))
            .count();
        long degraded = nodes.stream()
            .filter(node -> List.of("degraded", "partial").contains(String.valueOf(node.evidenceQuality().get("status"))))
            .count();
        result.put("stale_evidence_count", stale);
        result.put("degraded_evidence_count", degraded);
        result.put("quality_status", stale > 0 ? "stale" : degraded > 0 ? "partial" : "complete");
        return result;
    }

    private String strength(double confidence) {
        if (confidence >= 0.9) {
            return "strong";
        }
        if (confidence >= 0.7) {
            return "moderate";
        }
        return "weak";
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replace('_', ' ').replace('-', ' ').trim();
    }

    private record EdgeCandidate(
        TimelineNode source,
        TimelineNode target,
        CausalRelation relation,
        long distanceMillis
    ) {
    }
}
