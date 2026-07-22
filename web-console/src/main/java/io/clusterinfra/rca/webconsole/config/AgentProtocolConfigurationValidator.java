package io.clusterinfra.rca.webconsole.config;

import java.util.regex.Pattern;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
public class AgentProtocolConfigurationValidator implements InitializingBean {
    private static final Pattern VERSION_PATTERN = Pattern.compile(
        "^[vV]?\\d+(?:\\.\\d+){0,3}(?:[-+][0-9A-Za-z.-]+)?$"
    );

    private final RcaConsoleProperties properties;

    public AgentProtocolConfigurationValidator(RcaConsoleProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        RcaConsoleProperties.Agent agent = properties.getAgent();
        int currentProtocol = positiveInteger(
            agent.getProtocolVersion(),
            "RCA_AGENT_PROTOCOL_VERSION"
        );
        int minimumProtocol = positiveInteger(
            agent.getMinimumSupportedProtocolVersion(),
            "RCA_AGENT_MINIMUM_SUPPORTED_PROTOCOL_VERSION"
        );
        if (minimumProtocol > currentProtocol) {
            throw new IllegalStateException(
                "RCA_AGENT_MINIMUM_SUPPORTED_PROTOCOL_VERSION must not exceed "
                    + "RCA_AGENT_PROTOCOL_VERSION"
            );
        }
        validateVersion(agent.getMinimumSupportedVersion(), "RCA_AGENT_MINIMUM_SUPPORTED_VERSION");
        validateVersion(agent.getPlatformVersion(), "RCA_PLATFORM_VERSION");
        if (!agent.getExpectedVersion().isBlank()) {
            validateVersion(agent.getExpectedVersion(), "RCA_AGENT_EXPECTED_VERSION");
        }
        if (properties.getSecurity().getAgentBootstrapTokenTtlSeconds() < 60) {
            throw new IllegalStateException(
                "RCA_AGENT_BOOTSTRAP_TOKEN_TTL_SECONDS must be at least 60"
            );
        }
    }

    private int positiveInteger(String value, String propertyName) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Report a stable configuration error below.
        }
        throw new IllegalStateException(propertyName + " must be a positive integer");
    }

    private void validateVersion(String value, String propertyName) {
        if (value == null || !VERSION_PATTERN.matcher(value.trim()).matches()) {
            throw new IllegalStateException(propertyName + " must be a valid version");
        }
    }
}
