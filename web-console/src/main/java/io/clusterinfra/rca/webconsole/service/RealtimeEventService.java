package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentRealtimeEvent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RealtimeEvent;
import io.clusterinfra.rca.webconsole.persistence.EvidenceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RealtimeEventService {
    private final EvidenceRepository evidence;
    private final RcaConsoleProperties properties;

    public RealtimeEventService(EvidenceRepository evidence, RcaConsoleProperties properties) {
        this.evidence = evidence;
        this.properties = properties;
    }

    @Transactional
    public RealtimeEvent ingest(String clusterId, String nodeName, AgentRealtimeEvent event) {
        Instant observedAt = event.observedAtOrNow();
        String evidenceId = id("evidence");
        EvidenceBundle bundle = new EvidenceBundle(
            evidenceId,
            clusterId,
            nodeName,
            alertName(event.eventType()),
            observedAt,
            Map.of("ebpf", Map.of("events", List.of(Map.of(
                "event_type", event.eventType(),
                "component", event.component(),
                "severity", event.severity(),
                "observed_at", observedAt.toString(),
                "payload", event.payloadOrEmpty()
            ))))
        );
        evidence.saveAndEnqueue(
            bundle,
            "agent_ebpf",
            false,
            properties.getPipeline().getMaxAttempts()
        );
        RealtimeEvent saved = new RealtimeEvent(
            id("event"),
            evidenceId,
            clusterId,
            nodeName,
            event.eventType(),
            event.component(),
            event.severity(),
            observedAt,
            event.payloadOrEmpty(),
            Instant.now()
        );
        return evidence.saveRealtimeEvent(saved);
    }

    private static String alertName(String eventType) {
        return switch (eventType) {
            case "oom_kill" -> "OOMKillDetected";
            case "tcp_retransmit", "tcp_retransmit_spike" -> "TCPRetransmitSpike";
            case "dns_timeout", "dns_latency" -> "DNSTimeoutDetected";
            default -> "KernelRealtimeEvent";
        };
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
