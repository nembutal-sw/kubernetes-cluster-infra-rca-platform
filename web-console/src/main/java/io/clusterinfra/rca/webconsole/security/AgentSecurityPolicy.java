package io.clusterinfra.rca.webconsole.security;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AgentSecurityPolicy {
    public static final String DEFAULT_ENROLLMENT_AUDIENCE =
        "cluster-infra-rca-agent-enrollment";
    static final Duration MAX_LEGACY_UNBOUND_TOKEN_GRACE = Duration.ofDays(30);

    private final Set<String> kubernetesApiAudiences;
    private final Instant legacyUnboundTokenGraceUntil;
    private final Clock clock;

    @Autowired
    public AgentSecurityPolicy(RcaConsoleProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AgentSecurityPolicy(RcaConsoleProperties properties, Clock clock) {
        this.clock = clock;
        this.kubernetesApiAudiences = parseAudiences(
            properties.getSecurity().getKubernetesApiAudiences()
        );
        this.legacyUnboundTokenGraceUntil = parseLegacyGraceUntil(
            properties.getSecurity().getLegacyUnboundAgentTokenGraceUntil()
        );
    }

    public boolean isKubernetesApiAudience(String audience) {
        return audience != null && kubernetesApiAudiences.contains(audience.trim());
    }

    public boolean allowsLegacyUnboundAgentToken() {
        return clock.instant().isBefore(legacyUnboundTokenGraceUntil);
    }

    public Set<String> kubernetesApiAudiences() {
        return kubernetesApiAudiences;
    }

    public Instant legacyUnboundTokenGraceUntil() {
        return legacyUnboundTokenGraceUntil;
    }

    private Set<String> parseAudiences(String configured) {
        Set<String> values = Arrays.stream(normalized(configured).split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
        if (values.isEmpty()) {
            throw new IllegalStateException(
                "RCA_KUBERNETES_API_AUDIENCES must contain at least one audience"
            );
        }
        if (values.stream().anyMatch(this::invalidAudience)) {
            throw new IllegalStateException(
                "RCA_KUBERNETES_API_AUDIENCES contains an invalid audience"
            );
        }
        return values;
    }

    private Instant parseLegacyGraceUntil(String configured) {
        String value = normalized(configured);
        if (value.isEmpty()) {
            return Instant.EPOCH;
        }
        try {
            Instant deadline = Instant.parse(value);
            if (deadline.isAfter(clock.instant().plus(MAX_LEGACY_UNBOUND_TOKEN_GRACE))) {
                throw new IllegalStateException(
                    "RCA_LEGACY_UNBOUND_AGENT_TOKEN_GRACE_UNTIL must be no more than "
                        + MAX_LEGACY_UNBOUND_TOKEN_GRACE.toDays()
                        + " days in the future"
                );
            }
            return deadline;
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException(
                "RCA_LEGACY_UNBOUND_AGENT_TOKEN_GRACE_UNTIL must be an ISO-8601 UTC instant",
                exception
            );
        }
    }

    private boolean invalidAudience(String value) {
        return value.length() > 255
            || value.chars().anyMatch(Character::isWhitespace)
            || value.chars().anyMatch(Character::isISOControl);
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
