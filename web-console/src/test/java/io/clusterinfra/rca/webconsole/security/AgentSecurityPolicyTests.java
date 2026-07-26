package io.clusterinfra.rca.webconsole.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AgentSecurityPolicyTests {
    private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

    @Test
    void separatesDedicatedEnrollmentAudienceFromKubernetesApiAudiences() {
        AgentSecurityPolicy policy = policy(new RcaConsoleProperties());

        assertThat(policy.isKubernetesApiAudience("https://kubernetes.default.svc"))
            .isTrue();
        assertThat(policy.isKubernetesApiAudience(
            AgentSecurityPolicy.DEFAULT_ENROLLMENT_AUDIENCE
        )).isFalse();
    }

    @Test
    void deprecatedGlobalGraceRemainsDetectableForStartupRejection() {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        assertThat(policy(properties).legacyUnboundTokenGraceUntil())
            .isEqualTo(Instant.EPOCH);

        properties.getSecurity().setLegacyUnboundAgentTokenGraceUntil(
            "2026-07-28T00:00:00Z"
        );
        assertThat(policy(properties).legacyUnboundTokenGraceUntil())
            .isEqualTo(Instant.parse("2026-07-28T00:00:00Z"));
    }

    @Test
    void rejectsMalformedOrExcessiveLegacyGraceConfiguration() {
        RcaConsoleProperties malformed = new RcaConsoleProperties();
        malformed.getSecurity().setLegacyUnboundAgentTokenGraceUntil("tomorrow");
        assertThatThrownBy(() -> policy(malformed))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ISO-8601 UTC instant");

        RcaConsoleProperties excessive = new RcaConsoleProperties();
        excessive.getSecurity().setLegacyUnboundAgentTokenGraceUntil(
            "2026-08-27T00:00:01Z"
        );
        assertThatThrownBy(() -> policy(excessive))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no more than 30 days");
    }

    @Test
    void rejectsAnEmptyKubernetesApiAudienceSet() {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getSecurity().setKubernetesApiAudiences(" , ");

        assertThatThrownBy(() -> policy(properties))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must contain at least one audience");
    }

    private AgentSecurityPolicy policy(RcaConsoleProperties properties) {
        return new AgentSecurityPolicy(
            properties,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
