package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.analysis.IncidentCausalityRules;
import io.clusterinfra.rca.webconsole.analysis.IncidentCausalityRules.CausalRelation;
import io.clusterinfra.rca.webconsole.analysis.IncidentCausalityRules.SignalProfile;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.EvidenceBundle;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Incident;
import io.clusterinfra.rca.webconsole.domain.RcaModels.RcaReport;
import io.clusterinfra.rca.webconsole.persistence.IncidentRepository;
import io.clusterinfra.rca.webconsole.persistence.ReportRepository;
import io.clusterinfra.rca.webconsole.security.TokenService;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class IncidentCorrelationService {
    private final IncidentRepository incidents;
    private final ReportRepository reports;
    private final RcaConsoleProperties properties;
    private final TokenService tokens;
    private final IncidentCausalityRules causality;

    public IncidentCorrelationService(
        IncidentRepository incidents,
        ReportRepository reports,
        RcaConsoleProperties properties,
        TokenService tokens,
        IncidentCausalityRules causality
    ) {
        this.incidents = incidents;
        this.reports = reports;
        this.properties = properties;
        this.tokens = tokens;
        this.causality = causality;
    }

    public CorrelationDecision decide(RcaReport report, EvidenceBundle evidence) {
        long windowSeconds = Math.max(
            60L,
            properties.getIncident().getCorrelationWindowMinutes() * 60L
        );
        Instant from = evidence.collectedAt().minusSeconds(windowSeconds);
        Instant to = evidence.collectedAt().plusSeconds(windowSeconds);
        SignalProfile incoming = causality.profile(report);
        List<Incident> candidates = incidents.findRecentOpen(
            evidence.clusterId(),
            evidence.nodeName(),
            from,
            to,
            properties.getIncident().getCandidateLimit()
        );

        Candidate best = candidates.stream()
            .map(incident -> candidate(incident, incoming, report, evidence.collectedAt(), windowSeconds))
            .flatMap(Optional::stream)
            .filter(candidate -> candidate.score() >= properties.getIncident().getMinimumScore())
            .max(Comparator.comparingInt(Candidate::score)
                .thenComparing(candidate -> candidate.incident().lastSeenAt()))
            .orElse(null);

        long bucket = evidence.collectedAt().getEpochSecond() / windowSeconds;
        String dedupKey = tokens.sha256(String.join(
            "|",
            evidence.clusterId(),
            evidence.nodeName(),
            incoming.primaryFamily(),
            Long.toString(bucket)
        ));
        if (best == null) {
            Candidate recurrence = recurrenceCandidate(
                report,
                incoming,
                evidence,
                properties.getIncident().getRecurrenceLookbackHours()
            ).orElse(null);
            if (recurrence != null) {
                return new CorrelationDecision(
                    dedupKey,
                    null,
                    recurrence.relation().ruleId(),
                    recurrence.relation().relationship(),
                    recurrence.score(),
                    false,
                    incoming.primaryFamily(),
                    recurrence.incident().incidentId(),
                    recurrence.incident().recurrenceSequence() + 1
                );
            }
            return new CorrelationDecision(
                dedupKey,
                null,
                "new_incident",
                "new incident root signal",
                100,
                false,
                incoming.primaryFamily(),
                null,
                0
            );
        }
        return new CorrelationDecision(
            dedupKey,
            best.incident().incidentId(),
            best.relation().ruleId(),
            best.relation().relationship(),
            best.score(),
            best.promoteRootCause(),
            incoming.primaryFamily(),
            null,
            0
        );
    }

    private Optional<Candidate> recurrenceCandidate(
        RcaReport incomingReport,
        SignalProfile incoming,
        EvidenceBundle evidence,
        int lookbackHours
    ) {
        long hours = Math.max(1, lookbackHours);
        List<Incident> resolved = incidents.findRecentResolved(
            evidence.clusterId(),
            evidence.nodeName(),
            evidence.collectedAt().minusSeconds(hours * 3600L),
            evidence.collectedAt(),
            properties.getIncident().getCandidateLimit()
        );
        return resolved.stream()
            .map(incident -> strictRecurrenceCandidate(incident, incomingReport, incoming))
            .flatMap(Optional::stream)
            .max(Comparator.comparingInt(Candidate::score)
                .thenComparing(candidate -> Optional.ofNullable(candidate.incident().resolvedAt())
                    .orElse(Instant.EPOCH)));
    }

    private Optional<Candidate> strictRecurrenceCandidate(
        Incident incident,
        RcaReport incomingReport,
        SignalProfile incoming
    ) {
        if (sameAlert(incident, incomingReport)) {
            return Optional.of(new Candidate(
                incident,
                new CausalRelation(
                    "incident_recurrence_same_alert",
                    "the same alert recurred after resolution",
                    1.0
                ),
                100,
                false
            ));
        }
        RcaReport previousReport = incident.latestReportId() == null
            ? null
            : reports.findReport(incident.latestReportId()).orElse(null);
        SignalProfile previous = causality.profile(incident, previousReport);
        if (!previous.primaryFamily().equals(incoming.primaryFamily())) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(
            incident,
            new CausalRelation(
                "incident_recurrence_same_family",
                "the same infrastructure signal family recurred after resolution",
                0.9
            ),
            90,
            false
        ));
    }

    private Optional<Candidate> candidate(
        Incident incident,
        SignalProfile incoming,
        RcaReport incomingReport,
        Instant observedAt,
        long windowSeconds
    ) {
        RcaReport currentReport = incident.latestReportId() == null
            ? null
            : reports.findReport(incident.latestReportId()).orElse(null);
        SignalProfile current = causality.profile(incident, currentReport);
        Optional<CausalRelation> downstream = causality.bestRelation(current, incoming);
        Optional<CausalRelation> upstream = causality.bestRelation(incoming, current);
        CausalRelation relation;
        boolean promote;
        int baseScore;

        if (sameAlert(incident, incomingReport)) {
            relation = new CausalRelation("same_alert", "same alert recurred", 1.0);
            promote = false;
            baseScore = 100;
        } else if (upstream.isPresent()
            && causality.rootRank(incoming.primaryFamily()) < causality.rootRank(current.primaryFamily())) {
            relation = upstream.get();
            promote = true;
            baseScore = 86;
        } else if (downstream.isPresent()) {
            relation = downstream.get();
            promote = false;
            baseScore = 82;
        } else if (!java.util.Collections.disjoint(current.families(), incoming.families())) {
            relation = new CausalRelation(
                "shared_signal_family",
                "signals share an infrastructure subsystem",
                0.78
            );
            promote = false;
            baseScore = 76;
        } else {
            return Optional.empty();
        }

        long distance = Math.abs(Duration.between(incident.lastSeenAt(), observedAt).getSeconds());
        int timeScore = (int) Math.max(0, 14 - ((distance * 14) / Math.max(1, windowSeconds)));
        int score = Math.min(100, baseScore + timeScore);
        return Optional.of(new Candidate(incident, relation, score, promote));
    }

    private boolean sameAlert(Incident incident, RcaReport report) {
        String incomingAlert = String.valueOf(report.trigger().getOrDefault(
            "alert_name",
            report.summary().symptom()
        ));
        return incident.alertName().equalsIgnoreCase(incomingAlert);
    }

    public record CorrelationDecision(
        String dedupKey,
        String matchedIncidentId,
        String ruleId,
        String relationship,
        int score,
        boolean promoteRootCause,
        String primaryFamily,
        String recurrenceOfIncidentId,
        int recurrenceSequence
    ) {
        public boolean matched() {
            return matchedIncidentId != null;
        }

        public boolean recurrence() {
            return recurrenceOfIncidentId != null;
        }
    }

    private record Candidate(
        Incident incident,
        CausalRelation relation,
        int score,
        boolean promoteRootCause
    ) {
    }
}
