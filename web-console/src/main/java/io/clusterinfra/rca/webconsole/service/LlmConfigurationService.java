package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.LlmConfigurationInfo;
import io.clusterinfra.rca.webconsole.domain.RcaModels.LlmDiagnosticCheck;
import io.clusterinfra.rca.webconsole.domain.RcaModels.LlmDiagnosticResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.LlmProviderSetupOption;
import io.clusterinfra.rca.webconsole.domain.RcaModels.LlmSetupGuideResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class LlmConfigurationService {
    private static final Set<String> SUPPORTED_PROVIDERS = Set.of(
        "openai",
        "openai-sdk",
        "openai_compatible",
        "self_hosted",
        "anthropic",
        "claude",
        "google",
        "google-genai",
        "gemini",
        "ollama"
    );

    private final RcaConsoleProperties properties;
    private final Environment environment;

    public LlmConfigurationService(RcaConsoleProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    public LlmConfigurationInfo info() {
        RcaConsoleProperties.Llm llm = properties.getLlm();
        String provider = provider();
        String credentialProperty = credentialProperty(provider);
        String baseUrlProperty = baseUrlProperty(provider);
        boolean credentialRequired = credentialProperty != null && !credentialProperty.isBlank();
        boolean baseUrlRequired = isBaseUrlRequired(provider);
        return new LlmConfigurationInfo(
            llm.isEnabled(),
            provider.isBlank() ? "none" : provider,
            normalize(llm.getModel()),
            springAiChatModel(),
            credentialRequired,
            credentialRequired && !normalize(environment.getProperty(credentialProperty, "")).isBlank(),
            credentialProperty == null ? "" : credentialProperty,
            credentialEnv(provider),
            baseUrlRequired,
            baseUrlRequired && !normalize(environment.getProperty(baseUrlProperty, "")).isBlank(),
            baseUrlProperty == null ? "" : baseUrlProperty,
            baseUrlEnv(provider),
            llm.getTimeoutSeconds(),
            llm.getMaxAttempts(),
            llm.getMaxOutputTokens(),
            llm.getFailureThreshold(),
            llm.getCooldownSeconds()
        );
    }

    public LlmDiagnosticResponse diagnostics() {
        LlmConfigurationInfo info = info();
        List<LlmDiagnosticCheck> checks = new ArrayList<>();
        if (!info.enabled()) {
            checks.add(check(
                "enabled",
                "disabled",
                "LLM analyzer is disabled.",
                "Set RCA_LLM_ENABLED=true only after provider credentials are ready."
            ));
            return new LlmDiagnosticResponse("disabled", info, List.copyOf(checks));
        }

        addProviderCheck(info, checks);
        addModelCheck(info, checks);
        addSpringAiChatModelCheck(info, checks);
        addCredentialCheck(info, checks);
        addBaseUrlCheck(info, checks);
        boolean failed = checks.stream().anyMatch(check -> "fail".equals(check.status()));
        boolean warned = checks.stream().anyMatch(check -> "warn".equals(check.status()));
        String outcome = failed ? "action_required" : warned ? "warning" : "ready";
        return new LlmDiagnosticResponse(outcome, info, List.copyOf(checks));
    }

    public LlmSetupGuideResponse setupGuide() {
        return new LlmSetupGuideResponse(
            "docs/llm-analyzer.md",
            true,
            "Use environment variables, Docker/Compose env files, Kubernetes Secret, or an external secret manager. The Web Console never stores or renders API key values.",
            List.of(
                providerOption(
                    "openai",
                    "OpenAI",
                    "openai-sdk",
                    "SPRING_AI_OPENAI_SDK_API_KEY",
                    "",
                    true,
                    false,
                    List.of("gpt-model-name"),
                    "Use for OpenAI-hosted models through Spring AI OpenAI SDK."
                ),
                providerOption(
                    "anthropic",
                    "Anthropic Claude",
                    "anthropic",
                    "SPRING_AI_ANTHROPIC_API_KEY",
                    "",
                    true,
                    false,
                    List.of("claude-model-name"),
                    "Provider aliases: anthropic, claude."
                ),
                providerOption(
                    "gemini",
                    "Google Gemini",
                    "google-genai",
                    "SPRING_AI_GOOGLE_GENAI_API_KEY",
                    "",
                    true,
                    false,
                    List.of("gemini-model-name"),
                    "Provider aliases: gemini, google, google-genai."
                ),
                providerOption(
                    "ollama",
                    "Ollama / local model",
                    "ollama",
                    "",
                    "SPRING_AI_OLLAMA_BASE_URL",
                    false,
                    false,
                    List.of("llama3.1", "qwen2.5"),
                    "Use a network-reachable Ollama endpoint. Base URL defaults depend on the runtime environment."
                ),
                providerOption(
                    "openai_compatible",
                    "OpenAI-compatible endpoint",
                    "openai-sdk",
                    "SPRING_AI_OPENAI_SDK_API_KEY",
                    "SPRING_AI_OPENAI_SDK_BASE_URL",
                    true,
                    true,
                    List.of("provider-model-name"),
                    "Use for hosted gateways that implement the OpenAI-compatible API."
                ),
                providerOption(
                    "self_hosted",
                    "Self-hosted OpenAI-compatible model",
                    "openai-sdk",
                    "",
                    "SPRING_AI_OPENAI_SDK_BASE_URL",
                    false,
                    true,
                    List.of("local-rca-model"),
                    "Use only after the model endpoint is reachable from the Platform pod or process."
                )
            )
        );
    }

    private void addProviderCheck(LlmConfigurationInfo info, List<LlmDiagnosticCheck> checks) {
        if (info.provider().isBlank() || "none".equals(info.provider())) {
            checks.add(check(
                "provider",
                "fail",
                "LLM provider is not selected.",
                "Set RCA_LLM_PROVIDER to openai, anthropic, gemini, ollama, openai_compatible, or self_hosted."
            ));
            return;
        }
        if (!SUPPORTED_PROVIDERS.contains(info.provider())) {
            checks.add(check(
                "provider",
                "fail",
                "LLM provider is not supported: " + info.provider(),
                "Use a supported provider name or keep RCA_LLM_ENABLED=false."
            ));
            return;
        }
        checks.add(check("provider", "pass", "LLM provider is selected: " + info.provider(), ""));
    }

    private void addModelCheck(LlmConfigurationInfo info, List<LlmDiagnosticCheck> checks) {
        if (info.model().isBlank()) {
            checks.add(check(
                "model",
                "fail",
                "LLM model is not configured.",
                "Set RCA_LLM_MODEL to the provider model name."
            ));
            return;
        }
        checks.add(check("model", "pass", "LLM model is configured.", ""));
    }

    private void addSpringAiChatModelCheck(LlmConfigurationInfo info, List<LlmDiagnosticCheck> checks) {
        if (info.springAiChatModel().isBlank() || "none".equalsIgnoreCase(info.springAiChatModel())) {
            checks.add(check(
                "spring_ai_chat_model",
                "fail",
                "Spring AI chat model auto-configuration is disabled.",
                "Set RCA_SPRING_AI_CHAT_MODEL to the matching Spring AI provider, for example openai, anthropic, google-genai, or ollama."
            ));
            return;
        }
        checks.add(check("spring_ai_chat_model", "pass", "Spring AI chat model is configured.", ""));
    }

    private void addCredentialCheck(LlmConfigurationInfo info, List<LlmDiagnosticCheck> checks) {
        if (!info.credentialRequired()) {
            checks.add(check("credential", "pass", "Provider does not require a platform-managed API key.", ""));
            return;
        }
        if (!info.credentialConfigured()) {
            checks.add(check(
                "credential",
                "fail",
                "LLM credential is missing.",
                "Set " + info.credentialEnv() + " through environment variables or Kubernetes Secret."
            ));
            return;
        }
        checks.add(check("credential", "pass", "LLM credential is configured.", ""));
    }

    private void addBaseUrlCheck(LlmConfigurationInfo info, List<LlmDiagnosticCheck> checks) {
        if (!info.baseUrlRequired()) {
            checks.add(check("base_url", "pass", "Provider base URL uses Spring AI defaults.", ""));
            return;
        }
        if (!info.baseUrlConfigured()) {
            checks.add(check(
                "base_url",
                "fail",
                "Provider base URL is missing.",
                "Set " + info.baseUrlEnv() + " for " + info.provider() + "."
            ));
            return;
        }
        checks.add(check("base_url", "pass", "Provider base URL is configured.", ""));
    }

    private String provider() {
        return normalize(properties.getLlm().getProvider()).toLowerCase(Locale.ROOT);
    }

    private String springAiChatModel() {
        return normalize(environment.getProperty("spring.ai.model.chat", "none"));
    }

    private boolean isBaseUrlRequired(String provider) {
        return "openai_compatible".equals(provider) || "self_hosted".equals(provider);
    }

    private static String credentialProperty(String provider) {
        return switch (provider) {
            case "openai", "openai-sdk", "openai_compatible" -> "spring.ai.openai-sdk.api-key";
            case "anthropic", "claude" -> "spring.ai.anthropic.api-key";
            case "google", "google-genai", "gemini" -> "spring.ai.google.genai.api-key";
            case "self_hosted", "ollama" -> null;
            default -> "";
        };
    }

    private static String credentialEnv(String provider) {
        return switch (provider) {
            case "openai", "openai-sdk", "openai_compatible" -> "SPRING_AI_OPENAI_SDK_API_KEY";
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

    private static LlmDiagnosticCheck check(
        String key,
        String status,
        String message,
        String remediation
    ) {
        return new LlmDiagnosticCheck(key, status, message, remediation);
    }

    private static LlmProviderSetupOption providerOption(
        String provider,
        String displayName,
        String springAiChatModel,
        String credentialEnv,
        String baseUrlEnv,
        boolean credentialRequired,
        boolean baseUrlRequired,
        List<String> modelExamples,
        String note
    ) {
        return new LlmProviderSetupOption(
            provider,
            displayName,
            springAiChatModel,
            credentialEnv,
            baseUrlEnv,
            credentialRequired,
            baseUrlRequired,
            List.copyOf(modelExamples),
            note
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
