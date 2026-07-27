package io.clusterinfra.rca.webconsole.config;

import io.clusterinfra.rca.webconsole.security.OpaqueTokenKeyRing;
import java.net.URI;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
            properties.getDefaultAdminUsername(),
            Set.of(""),
            "RCA_DEFAULT_ADMIN_USERNAME is required for initial production bootstrap",
            violations
        );
        rejectUnsafe(
            properties.getDefaultAdminPassword(),
            Set.of("", "admin"),
            "RCA_DEFAULT_ADMIN_PASSWORD is required and must not use a default password",
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
        validateGitOps(violations);
        validateReviewerCredentialLifecycle(violations);
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
        String opaqueTokenPepper = properties.getSecurity().getOpaqueTokenPepper();
        rejectUnsafe(
            opaqueTokenPepper,
            Set.of("", "development-only-opaque-token-pepper"),
            "RCA_OPAQUE_TOKEN_PEPPER must be a non-default secret",
            violations
        );
        if (opaqueTokenPepper.length() < 32) {
            violations.add("RCA_OPAQUE_TOKEN_PEPPER must contain at least 32 characters");
        }
        if (MessageDigest.isEqual(
            opaqueTokenPepper.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            properties.getSecurity().getEncryptionSecret()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )) {
            violations.add("RCA_OPAQUE_TOKEN_PEPPER must be different from RCA_ENCRYPTION_SECRET");
        }
        validateOpaqueTokenKeyRing(violations, opaqueTokenPepper);
        if (properties.getSecurity().getStandardRequestMaxBytes() < 1024
            || properties.getSecurity().getEvidenceRequestMaxBytes()
                < properties.getSecurity().getStandardRequestMaxBytes()) {
            violations.add("request body size limits are invalid");
        }
        if (properties.getSecurity().getManifestTokenTtlSeconds() < 30
            || properties.getSecurity().getManifestTokenTtlSeconds() > 900) {
            violations.add("RCA_MANIFEST_TOKEN_TTL_SECONDS must be between 30 and 900");
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                "Unsafe production configuration:\n - " + String.join("\n - ", violations)
            );
        }
    }

    private void validateOpaqueTokenKeyRing(
        List<String> violations,
        String currentPepper
    ) {
        Map<String, String> previousKeys;
        try {
            previousKeys = OpaqueTokenKeyRing.parsePreviousKeys(
                properties.getSecurity().getOpaqueTokenPreviousKeys()
            );
        } catch (IllegalStateException exception) {
            violations.add(exception.getMessage());
            return;
        }
        try {
            OpaqueTokenKeyRing.from(properties.getSecurity());
        } catch (IllegalStateException exception) {
            violations.add(exception.getMessage());
        }
        for (String previousPepper : previousKeys.values()) {
            if (previousPepper.length() < 32) {
                violations.add(
                    "RCA_OPAQUE_TOKEN_PREVIOUS_KEYS peppers must contain "
                        + "at least 32 characters"
                );
            }
            if (UNSAFE_SECRETS.contains(previousPepper)
                || "development-only-opaque-token-pepper".equals(previousPepper)) {
                violations.add(
                    "RCA_OPAQUE_TOKEN_PREVIOUS_KEYS must not contain default secrets"
                );
            }
            if (constantTimeEqual(
                previousPepper,
                properties.getSecurity().getEncryptionSecret()
            )) {
                violations.add(
                    "RCA_OPAQUE_TOKEN_PREVIOUS_KEYS must be different from "
                        + "RCA_ENCRYPTION_SECRET"
                );
            }
            if (constantTimeEqual(previousPepper, currentPepper)) {
                violations.add(
                    "RCA_OPAQUE_TOKEN_PREVIOUS_KEYS must be different from "
                        + "RCA_OPAQUE_TOKEN_PEPPER"
                );
            }
        }
    }

    private boolean constantTimeEqual(String first, String second) {
        return MessageDigest.isEqual(
            normalized(first).getBytes(java.nio.charset.StandardCharsets.UTF_8),
            normalized(second).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
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
        if (!Double.isFinite(properties.getLlm().getInputCostPerMillionTokens())
            || properties.getLlm().getInputCostPerMillionTokens() < 0) {
            violations.add("RCA_LLM_INPUT_COST_PER_MILLION_TOKENS must be a non-negative number");
        }
        if (!Double.isFinite(properties.getLlm().getOutputCostPerMillionTokens())
            || properties.getLlm().getOutputCostPerMillionTokens() < 0) {
            violations.add("RCA_LLM_OUTPUT_COST_PER_MILLION_TOKENS must be a non-negative number");
        }
        if (!properties.getLlm().isEnabled()) {
            return;
        }
        String provider = normalized(properties.getLlm().getProvider()).toLowerCase(Locale.ROOT);
        String model = normalized(properties.getLlm().getModel());
        String chatModel = normalized(environment.getProperty("spring.ai.model.chat", ""));
        int providerRetryMaxAttempts = environment.getProperty("spring.ai.retry.max-attempts", Integer.class, 1);
        if (provider.isEmpty() || "none".equals(provider)) {
            violations.add("RCA_LLM_PROVIDER is required when RCA_LLM_ENABLED=true");
        }
        if (model.isEmpty()) {
            violations.add("RCA_LLM_MODEL is required when RCA_LLM_ENABLED=true");
        }
        if (chatModel.isEmpty() || "none".equalsIgnoreCase(chatModel)) {
            violations.add("RCA_SPRING_AI_CHAT_MODEL is required when RCA_LLM_ENABLED=true");
        }
        if (providerRetryMaxAttempts < 1 || providerRetryMaxAttempts > 3) {
            violations.add("RCA_SPRING_AI_RETRY_MAX_ATTEMPTS must be between 1 and 3");
        }

        String credentialProperty = switch (provider) {
            case "openai", "openai-sdk", "openai_compatible" -> "spring.ai.openai-sdk.api-key";
            case "anthropic", "claude" -> "spring.ai.anthropic.api-key";
            case "google", "google-genai", "gemini" -> "spring.ai.google.genai.api-key";
            case "self_hosted", "ollama" -> null;
            default -> "";
        };
        if ("".equals(credentialProperty)) {
            violations.add("RCA_LLM_PROVIDER is not supported: " + provider);
        } else if (credentialProperty != null
            && !hasAnyConfiguredValue(credentialProperty, "RCA_LLM_API_KEY")) {
            violations.add(credentialProperty + " or RCA_LLM_API_KEY is required for the configured LLM provider");
        }
        if (("openai_compatible".equals(provider) || "self_hosted".equals(provider))
            && !hasAnyConfiguredValue("spring.ai.openai-sdk.base-url", "RCA_LLM_BASE_URL")) {
            violations.add(
                "spring.ai.openai-sdk.base-url or RCA_LLM_BASE_URL is required for RCA_LLM_PROVIDER=" + provider
            );
        }
    }

    private boolean hasAnyConfiguredValue(String... propertyNames) {
        for (String propertyName : propertyNames) {
            if (!normalized(environment.getProperty(propertyName, "")).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void validateNotification(List<String> violations) {
        RcaConsoleProperties.Notification notification = properties.getNotification();
        if (!notification.isEnabled()) {
            return;
        }
        String slackWebhookUrl = notification.getSlackWebhookUrl();
        String webhookUrl = notification.getWebhookUrl();
        if (slackWebhookUrl.isBlank() && webhookUrl.isBlank()) {
            violations.add(
                "RCA_SLACK_WEBHOOK_URL or RCA_NOTIFICATION_WEBHOOK_URL is required when RCA_NOTIFICATION_ENABLED=true"
            );
        }
        validateHttpsUrl(slackWebhookUrl, "RCA_SLACK_WEBHOOK_URL", violations);
        validateHttpsUrl(webhookUrl, "RCA_NOTIFICATION_WEBHOOK_URL", violations);
        if (webhookUrl.isBlank() && !notification.getWebhookToken().isBlank()) {
            violations.add("RCA_NOTIFICATION_WEBHOOK_TOKEN requires RCA_NOTIFICATION_WEBHOOK_URL");
        }
        if (notification.getMaxAttempts() < 1 || notification.getMaxAttempts() > 10) {
            violations.add("RCA_NOTIFICATION_MAX_ATTEMPTS must be between 1 and 10");
        }
        if (notification.getTimeoutSeconds() < 1 || notification.getTimeoutSeconds() > 30) {
            violations.add("RCA_NOTIFICATION_TIMEOUT_SECONDS must be between 1 and 30");
        }
        if (notification.getBatchSize() < 1 || notification.getBatchSize() > 100) {
            violations.add("RCA_NOTIFICATION_BATCH_SIZE must be between 1 and 100");
        }
        if (notification.getPollIntervalMs() < 100 || notification.getPollIntervalMs() > 60000) {
            violations.add("RCA_NOTIFICATION_POLL_INTERVAL_MS must be between 100 and 60000");
        }
        if (notification.getInitialDelayMs() < 0 || notification.getInitialDelayMs() > 600000) {
            violations.add("RCA_NOTIFICATION_INITIAL_DELAY_MS must be between 0 and 600000");
        }
        if (notification.getLeaseSeconds() < 15 || notification.getLeaseSeconds() > 3600) {
            violations.add("RCA_NOTIFICATION_LEASE_SECONDS must be between 15 and 3600");
        }
        if (notification.getRetryBaseSeconds() < 1
            || notification.getRetryMaxSeconds() < notification.getRetryBaseSeconds()
            || notification.getRetryMaxSeconds() > 86400) {
            violations.add(
                "RCA_NOTIFICATION_RETRY_SECONDS must be positive, ordered, and no greater than 86400"
            );
        }
    }

    private void validateGitOps(List<String> violations) {
        RcaConsoleProperties.GitOps gitOps = properties.getGitOps();
        if (!gitOps.isEnabled()) {
            return;
        }
        String provider = gitOps.getProvider().toLowerCase(Locale.ROOT);
        if (!Set.of("github", "gitlab", "gitea").contains(provider)) {
            violations.add("RCA_GITOPS_PROVIDER must be github, gitlab, or gitea");
        }
        boolean validRepository = Set.of("github", "gitea").contains(provider)
            ? gitOps.getRepository().matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
            : gitOps.getRepository().matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+");
        if (!validRepository) {
            violations.add("RCA_GITOPS_REPOSITORY is invalid for the configured provider");
        }
        validateHttpsUrl(gitOps.getApiBaseUrl(), "RCA_GITOPS_API_BASE_URL", violations);
        if ("gitea".equals(provider) && gitOps.getApiBaseUrl().isBlank()) {
            violations.add("RCA_GITOPS_API_BASE_URL is required for gitea");
        }
        rejectUnsafe(
            gitOps.getToken(),
            UNSAFE_SECRETS,
            "RCA_GITOPS_TOKEN must be a non-default secret when GitOps is enabled",
            violations
        );
        rejectUnsafe(
            gitOps.getWebhookSecret(),
            UNSAFE_SECRETS,
            "RCA_GITOPS_WEBHOOK_SECRET must be a non-default secret when GitOps is enabled",
            violations
        );
    }

    private void validateReviewerCredentialLifecycle(List<String> violations) {
        int expiringSeconds = properties.getAgent().getReviewerCredentialExpiringSeconds();
        int maximumGraceSeconds = properties.getAgent()
            .getReviewerCredentialMaximumGraceSeconds();
        if (expiringSeconds < 60 || expiringSeconds > 86400) {
            violations.add(
                "RCA_REVIEWER_CREDENTIAL_EXPIRING_SECONDS must be between 60 and 86400"
            );
        }
        if (maximumGraceSeconds < 60 || maximumGraceSeconds > 604800) {
            violations.add(
                "RCA_REVIEWER_CREDENTIAL_MAXIMUM_GRACE_SECONDS must be between 60 and 604800"
            );
        }
    }

    private void validateHttpsUrl(String value, String label, List<String> violations) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                violations.add(label + " must be an absolute HTTPS URL");
            }
        } catch (IllegalArgumentException exception) {
            violations.add(label + " must be an absolute HTTPS URL");
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
