package io.clusterinfra.rca.webconsole.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class ProductionSecurityValidator implements InitializingBean {
    static final int MAX_PRODUCTION_SESSION_TTL_HOURS = 24;
    private static final Set<String> UNSAFE_DB_PASSWORDS = Set.of(
        "",
        "admin",
        "change-me",
        "changeme",
        "password",
        "rca_password",
        "rca-password"
    );
    private static final Set<String> UNSAFE_SECRETS = Set.of(
        "",
        "change-me",
        "changeme",
        "dev-secret",
        "development-secret"
    );

    private final Environment environment;
    private final RcaConsoleProperties properties;

    public ProductionSecurityValidator(Environment environment, RcaConsoleProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            validateProductionConfiguration();
        }
    }

    void validateProductionConfiguration() {
        List<String> violations = new ArrayList<>();
        rejectUnsafe(
            properties.getDefaultAdminPassword(),
            Set.of("", "admin"),
            "RCA_DEFAULT_ADMIN_PASSWORD must not use the default admin password",
            violations
        );
        rejectUnsafe(
            properties.getWebhookToken(),
            Set.of("", "dev-webhook-token"),
            "RCA_WEBHOOK_TOKEN must be a non-default secret",
            violations
        );
        rejectUnsafe(
            environment.getProperty("spring.datasource.password", ""),
            UNSAFE_DB_PASSWORDS,
            "RCA_DB_PASSWORD must be a non-default password",
            violations
        );
        if (properties.getSessionTtlHours() < 1
            || properties.getSessionTtlHours() > MAX_PRODUCTION_SESSION_TTL_HOURS) {
            violations.add(
                "RCA_SESSION_TTL_HOURS must be between 1 and "
                    + MAX_PRODUCTION_SESSION_TTL_HOURS
            );
        }
        validatePublicBaseUrl(violations);
        validateLlm(violations);
        validateNotification(violations);
        if (properties.getObservability().isEnabled()) {
            rejectUnsafe(
                properties.getObservability().getMetricsToken(),
                UNSAFE_SECRETS,
                "RCA_METRICS_TOKEN must be a non-default secret when observability is enabled",
                violations
            );
        }
        if (properties.getDemo().isEnabled()) {
            violations.add("RCA_DEMO_ENABLED must be false in production");
        }
        if (!properties.getAudit().isEnabled()) {
            violations.add("RCA_AUDIT_ENABLED must be true in production");
        }
        rejectUnsafe(
            properties.getSecurity().getEncryptionSecret(),
            UNSAFE_SECRETS,
            "RCA_ENCRYPTION_SECRET must be a non-default secret",
            violations
        );
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                "Unsafe production configuration:\n - " + String.join("\n - ", violations)
            );
        }
    }

    private void validatePublicBaseUrl(List<String> violations) {
        String publicBaseUrl = normalized(properties.getPublicApiBaseUrl());
        try {
            URI uri = URI.create(publicBaseUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                violations.add("RCA_PUBLIC_API_BASE_URL must be an absolute HTTPS URL");
            }
        } catch (IllegalArgumentException exception) {
            violations.add("RCA_PUBLIC_API_BASE_URL must be an absolute HTTPS URL");
        }
    }

    private void validateLlm(List<String> violations) {
        if (!properties.getLlm().isEnabled()) {
            return;
        }
        String provider = normalized(properties.getLlm().getProvider()).toLowerCase(Locale.ROOT);
        String model = normalized(properties.getLlm().getModel());
        String chatModel = normalized(environment.getProperty("spring.ai.model.chat", ""));
        if (provider.isEmpty() || "none".equals(provider)) {
            violations.add("RCA_LLM_PROVIDER is required when RCA_LLM_ENABLED=true");
        }
        if (model.isEmpty()) {
            violations.add("RCA_LLM_MODEL is required when RCA_LLM_ENABLED=true");
        }
        if (chatModel.isEmpty() || "none".equalsIgnoreCase(chatModel)) {
            violations.add("RCA_SPRING_AI_CHAT_MODEL is required when RCA_LLM_ENABLED=true");
        }

        String credentialProperty = switch (provider) {
            case "openai", "openai-sdk" -> "spring.ai.openai-sdk.api-key";
            case "anthropic", "claude" -> "spring.ai.anthropic.api-key";
            case "google", "google-genai", "gemini" -> "spring.ai.google.genai.api-key";
            case "ollama" -> null;
            default -> "";
        };
        if ("".equals(credentialProperty)) {
            violations.add("RCA_LLM_PROVIDER is not supported: " + provider);
        } else if (credentialProperty != null
            && normalized(environment.getProperty(credentialProperty, "")).isEmpty()) {
            violations.add(credentialProperty + " is required for the configured LLM provider");
        }
    }

    private void validateNotification(List<String> violations) {
        if (!properties.getNotification().isEnabled()) {
            return;
        }
        String webhookUrl = properties.getNotification().getSlackWebhookUrl();
        try {
            URI uri = URI.create(webhookUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                violations.add("RCA_SLACK_WEBHOOK_URL must be an absolute HTTPS URL");
            }
        } catch (IllegalArgumentException exception) {
            violations.add("RCA_SLACK_WEBHOOK_URL must be an absolute HTTPS URL");
        }
    }

    private void rejectUnsafe(
        String value,
        Set<String> unsafeValues,
        String message,
        List<String> violations
    ) {
        if (unsafeValues.contains(normalized(value).toLowerCase(Locale.ROOT))) {
            violations.add(message);
        }
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
