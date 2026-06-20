package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Incident;
import io.clusterinfra.rca.webconsole.domain.RcaModels.IncidentTimeline;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RealtimeEvent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.TimelineEdge;
import io.clusterinfra.rca.webconsole.domain.RcaModels.TimelineNode;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

@Service
public class IncidentTimelineService {
    private final EvidenceRepository evidence;
    private final RuleBasedRcaAnalyzer analyzer;

    public IncidentTimelineService(EvidenceRepository evidence, RuleBasedRcaAnalyzer analyzer) {
        this.evidence = evidence;
        this.analyzer = analyzer;
    }

    public IncidentTimeline build(Incident incident) {
        Instant from = incident.firstSeenAt().minus(10, ChronoUnit.MINUTES);
        Instant to = incident.lastSeenAt().plus(10, ChronoUnit.MINUTES);
        List<TimelineNode> nodes = new ArrayList<>();
        Set<String> realtimeEvidence = new HashSet<>();
        AtomicInteger sequence = new AtomicInteger();

        for (RealtimeEvent event : evidence.listRealtimeEvents(
            incident.clusterId(), incident.nodeName(), from, to
        )) {
            realtimeEvidence.add(event.evidenceId());
            nodes.add(new TimelineNode(
                "timeline-" + sequence.incrementAndGet(),
                event.observedAt(),
                event.component(),
                event.eventType(),
                event.severity(),
                title(event.eventType()),
                detail(event.payload()),
                event.evidenceId(),
                false
            ));
        }
        for (EvidenceBundle bundle : evidence.listForNodeWindow(
            incident.clusterId(), incident.nodeName(), from, to
        )) {
            if (realtimeEvidence.contains(bundle.evidenceId())) {
                continue;
            }
            List<Map<String, Object>> signals = analyzer.deriveTimelineSignals(bundle.collectors());
            if (signals.isEmpty()) {
                nodes.add(new TimelineNode(
                    "timeline-" + sequence.incrementAndGet(),
                    bundle.collectedAt(),
                    "kubernetes",
                    bundle.alertName(),
                    "info",
                    bundle.alertName(),
                    "Evidence was collected for this incident.",
                    bundle.evidenceId(),
                    false
                ));
                continue;
            }
            for (Map<String, Object> signal : signals) {
                nodes.add(new TimelineNode(
                    "timeline-" + sequence.incrementAndGet(),
                    bundle.collectedAt(),
                    String.valueOf(signal.getOrDefault("component", "node")),
                    String.valueOf(signal.getOrDefault("signal", bundle.alertName())),
                    String.valueOf(signal.getOrDefault("severity", "warning")),
                    title(String.valueOf(signal.getOrDefault("signal", bundle.alertName()))),
                    String.valueOf(signal.getOrDefault("interpretation", "")),
                    bundle.evidenceId(),
                    false
                ));
            }
        }
        nodes.sort(Comparator.comparing(TimelineNode::timestamp).thenComparing(TimelineNode::id));
        if (!nodes.isEmpty()) {
            int rootIndex = -1;
            for (int index = 0; index < nodes.size(); index++) {
                if (Objects.equals(incident.latestEvidenceId(), nodes.get(index).evidenceId())) {
                    rootIndex = index;
                    break;
                }
            }
            if (rootIndex < 0) {
                rootIndex = 0;
                for (int index = 0; index < nodes.size(); index++) {
                    if ("critical".equalsIgnoreCase(nodes.get(index).severity())) {
                        rootIndex = index;
                        break;
                    }
                }
            }
            TimelineNode root = nodes.get(rootIndex);
            nodes.set(rootIndex, new TimelineNode(
                root.id(), root.timestamp(), root.component(), root.eventType(), root.severity(),
                root.title(), root.detail(), root.evidenceId(), true
            ));
        }
        List<TimelineEdge> edges = new ArrayList<>();
        for (int index = 1; index < nodes.size(); index++) {
            TimelineNode source = nodes.get(index - 1);
            TimelineNode target = nodes.get(index);
            edges.add(new TimelineEdge(source.id(), target.id(), relationship(source, target)));
        }
        return new IncidentTimeline(incident.incidentId(), from, to, List.copyOf(nodes), List.copyOf(edges));
    }

    private static String title(String value) {
        return value.replace('_', ' ').replace('-', ' ');
    }

    private static String detail(Map<String, Object> payload) {
        Object summary = payload.get("summary");
        return summary == null ? payload.toString() : String.valueOf(summary);
    }

    private static String relationship(TimelineNode source, TimelineNode target) {
        if ("disk".equals(source.component()) && Set.of("kubelet", "kubernetes").contains(target.component())) {
            return "storage pressure propagated to node control";
        }
        if ("memory".equals(source.component())) {
            return "memory pressure propagated";
        }
        if (Set.of("network", "dns", "conntrack", "cni").contains(source.component())) {
            return "network path degradation propagated";
        }
        return "followed by";
    }
}
