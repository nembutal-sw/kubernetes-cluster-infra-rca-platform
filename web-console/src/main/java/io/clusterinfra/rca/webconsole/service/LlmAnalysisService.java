package io.clusterinfra.rca.webconsole.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openaisdk.OpenAiSdkChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class LlmAnalysisService {
    private static final String SYSTEM_PROMPT = """
        You are a Kubernetes node and Linux infrastructure RCA assistant.
        Use only the supplied preprocessed evidence. Never claim that a remediation was executed.
        Return JSON only with keys:
        summary, root_cause_candidates, action_suggestions, additional_checks.
        Each action_suggestion must contain action_key, action, and reason.
        Prefer read-only verification. Treat destructive or mutating operations as human-reviewed suggestions.
        """;

    private final ObjectProvider<ChatModel> chatModels;
    private final ObjectMapper objectMapper;
    private final RcaConsoleProperties properties;

    public LlmAnalysisService(
        ObjectProvider<ChatModel> chatModels,
        ObjectMapper objectMapper,
        RcaConsoleProperties properties
    ) {
        this.chatModels = chatModels;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Map<String, Object> analyze(Map<String, Object> payload) {
        if (!properties.getLlm().isEnabled()) {
            return Map.of("status", "skipped", "reason", "llm analyzer disabled");
        }
        ChatModel chatModel = chatModels.orderedStream().findFirst().orElse(null);
        if (chatModel == null) {
            return Map.of("status", "skipped", "reason", "spring ai chat model not configured");
        }
        try {
            String input = objectMapper.writeValueAsString(payload);
            ChatClient.ChatClientRequestSpec request = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .system(SYSTEM_PROMPT)
                .user(input);
            ChatOptions options = chatOptions();
            if (options != null) {
                request = request.options(options);
            }
            String content = request.call().content();
            Map<String, Object> result = objectMapper.readValue(stripFence(content), new TypeReference<>() {
            });
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "completed");
            response.put("provider", properties.getLlm().getProvider());
            response.put("model", properties.getLlm().getModel());
            response.put("result", result);
            return response;
        } catch (Exception exception) {
            return Map.of(
                "status", "failed",
                "provider", properties.getLlm().getProvider(),
                "error", exception.getClass().getSimpleName() + ": " + safeMessage(exception)
            );
        }
    }

    private String stripFence(String value) {
        String content = value == null ? "" : value.trim();
        if (content.startsWith("```")) {
            int firstLine = content.indexOf('\n');
            int lastFence = content.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                return content.substring(firstLine + 1, lastFence).trim();
            }
        }
        return content;
    }

    private ChatOptions chatOptions() {
        String provider = properties.getLlm().getProvider().trim().toLowerCase(Locale.ROOT);
        String model = properties.getLlm().getModel().trim();
        int maxTokens = Math.max(128, properties.getLlm().getMaxOutputTokens());
        return switch (provider) {
            case "openai", "openai-sdk", "openai_compatible", "self_hosted" -> {
                OpenAiSdkChatOptions.Builder builder = OpenAiSdkChatOptions.builder()
                    .maxCompletionTokens(maxTokens)
                    .internalToolExecutionEnabled(false);
                if (!model.isBlank()) {
                    builder.model(model);
                }
                yield builder.build();
            }
            case "anthropic", "claude" -> {
                AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder()
                    .maxTokens(maxTokens)
                    .internalToolExecutionEnabled(false);
                if (!model.isBlank()) {
                    builder.model(model);
                }
                yield builder.build();
            }
            case "gemini", "google", "google-genai" -> {
                GoogleGenAiChatOptions.Builder builder = GoogleGenAiChatOptions.builder()
                    .maxOutputTokens(maxTokens)
                    .internalToolExecutionEnabled(false);
                if (!model.isBlank()) {
                    builder.model(model);
                }
                yield builder.build();
            }
            case "ollama" -> {
                OllamaChatOptions.Builder builder = OllamaChatOptions.builder()
                    .numPredict(maxTokens)
                    .internalToolExecutionEnabled(false);
                if (!model.isBlank()) {
                    builder.model(model);
                }
                yield builder.build();
            }
            default -> null;
        };
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "analysis failed";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
