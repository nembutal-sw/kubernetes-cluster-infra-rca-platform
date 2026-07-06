package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ExportSecurityInfo;
import io.clusterinfra.rca.webconsole.domain.RcaModels.LlmConfigurationInfo;
import io.clusterinfra.rca.webconsole.domain.RcaModels.PlatformInfo;
import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlatformInfoController {
    private final RcaConsoleProperties properties;
    private final Environment environment;

    public PlatformInfoController(RcaConsoleProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @GetMapping({"/api/platform/info", "/api/v1/platform/info"})
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER','AUDITOR')")
    public PlatformInfo info() {
        RcaConsoleProperties.Agent agent = properties.getAgent();
        RcaConsoleProperties.Export export = properties.getExport();
        boolean bundleSignatureEnabled = !export.getSignatureSecret().isBlank();
        return new PlatformInfo(
            agent.getPlatformVersion(),
            "v1",
            agent.getProtocolVersion(),
            agent.getMinimumSupportedProtocolVersion(),
            agent.getMinimumSupportedVersion(),
            new ExportSecurityInfo(
                export.getMaxBundleBytes(),
                "SHA-256",
                bundleSignatureEnabled,
                bundleSignatureEnabled ? "HMAC-SHA256" : "none",
                bundleSignatureEnabled ? export.getSignatureKeyId() : "",
                "scripts/verify_evidence_bundle.py"
            ),
            llmInfo()
        );
    }

    private LlmConfigurationInfo llmInfo() {
        RcaConsoleProperties.Llm llm = properties.getLlm();
        String provider = normalize(llm.getProvider()).toLowerCase(Locale.ROOT);
        String credentialProperty = credentialProperty(provider);
        String baseUrlProperty = baseUrlProperty(provider);
        boolean credentialRequired = credentialProperty != null && !credentialProperty.isBlank()
            && !"self_hosted".equals(provider);
        boolean baseUrlRequired = "openai_compatible".equals(provider) || "self_hosted".equals(provider);
        return new LlmConfigurationInfo(
            llm.isEnabled(),
            provider.isBlank() ? "none" : provider,
            normalize(llm.getModel()),
            normalize(environment.getProperty("spring.ai.model.chat", "none")),
            credentialRequired,
            credentialProperty != null && !credentialProperty.isBlank()
                && !normalize(environment.getProperty(credentialProperty, "")).isBlank(),
            credentialProperty == null ? "" : credentialProperty,
            credentialEnv(provider),
            baseUrlRequired,
            baseUrlProperty != null && !baseUrlProperty.isBlank()
                && !normalize(environment.getProperty(baseUrlProperty, "")).isBlank(),
            baseUrlProperty == null ? "" : baseUrlProperty,
            baseUrlEnv(provider),
            llm.getTimeoutSeconds(),
            llm.getMaxAttempts(),
            llm.getMaxOutputTokens(),
            llm.getFailureThreshold(),
            llm.getCooldownSeconds()
        );
    }

    private static String credentialProperty(String provider) {
        return switch (provider) {
            case "openai", "openai-sdk", "openai_compatible", "self_hosted" -> "spring.ai.openai-sdk.api-key";
            case "anthropic", "claude" -> "spring.ai.anthropic.api-key";
            case "google", "google-genai", "gemini" -> "spring.ai.google.genai.api-key";
            case "ollama" -> null;
            default -> "";
        };
    }

    private static String credentialEnv(String provider) {
        return switch (provider) {
            case "openai", "openai-sdk", "openai_compatible", "self_hosted" -> "SPRING_AI_OPENAI_SDK_API_KEY";
            case "anthropic", "claude" -> "SPRING_AI_ANTHROPIC_API_KEY";
            case "google", "google-genai", "gemini" -> "SPRING_AI_GOOGLE_GENAI_API_KEY";
            default -> "";
        };
    }

    private static String baseUrlProperty(String provider) {
        return switch (provider) {
            case "openai_compatible", "self_hosted" -> "spring.ai.openai-sdk.base-url";
            case "ollama" -> "spring.ai.ollama.base-url";
            default -> "";
        };
    }

    private static String baseUrlEnv(String provider) {
        return switch (provider) {
            case "openai_compatible", "self_hosted" -> "SPRING_AI_OPENAI_SDK_BASE_URL";
            case "ollama" -> "SPRING_AI_OLLAMA_BASE_URL";
            default -> "";
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
