package io.clusterinfra.rca.webconsole.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AgentProtocolConfigurationValidatorTests {
    @Test
    void defaultProtocolConfigurationIsValid() {
        RcaConsoleProperties properties = new RcaConsoleProperties();

        assertThatCode(() -> new AgentProtocolConfigurationValidator(properties).afterPropertiesSet())
            .doesNotThrowAnyException();
    }

    @Test
    void minimumProtocolCannotExceedPlatformProtocol() {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getAgent().setProtocolVersion("1");
        properties.getAgent().setMinimumSupportedProtocolVersion("2");

        assertThatThrownBy(
            () -> new AgentProtocolConfigurationValidator(properties).afterPropertiesSet()
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must not exceed");
    }

    @Test
    void malformedVersionsAreRejected() {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getAgent().setMinimumSupportedVersion("latest");

        assertThatThrownBy(
            () -> new AgentProtocolConfigurationValidator(properties).afterPropertiesSet()
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("RCA_AGENT_MINIMUM_SUPPORTED_VERSION");
    }
}
