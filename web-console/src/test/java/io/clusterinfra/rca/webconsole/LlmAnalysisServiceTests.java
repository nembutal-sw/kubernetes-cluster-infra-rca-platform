package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.service.LlmAnalysisService;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

class LlmAnalysisServiceTests {
    private LlmAnalysisService service;

    @AfterEach
    void closeExecutor() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void validatesAndBoundsProviderResponse() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response("""
            {
              "summary": "Storage pressure",
              "root_cause_candidates": [
                {"cause": "inode exhaustion", "confidence": "HIGH", "supporting_evidence": ["inode=98%"]}
              ],
              "action_suggestions": [
                {"action_key": "../../restart", "action": "Inspect storage", "reason": "Confirm the signal"}
              ],
              "additional_checks": ["df -i"],
              "untrusted_extra": "discard me"
            }
            """));
        service = service(model, properties());

        Map<String, Object> result = service.analyze(Map.of("schema_version", "1.0"));

        assertThat(result.get("status")).isEqualTo("completed");
        @SuppressWarnings("unchecked")
        Map<String, Object> normalized = (Map<String, Object>) result.get("result");
        assertThat(normalized).containsOnlyKeys(
            "summary",
            "root_cause_candidates",
            "action_suggestions",
            "additional_checks"
        );
        assertThat(normalized.toString())
            .contains("inode exhaustion")
            .contains("manual_investigation")
            .doesNotContain("untrusted_extra");
    }

    @Test
    void retriesThenOpensCircuitWithoutBreakingRcaPipeline() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(
            new IllegalStateException("authorization=secret-token")
        );
        RcaConsoleProperties properties = properties();
        properties.getLlm().setMaxAttempts(2);
        properties.getLlm().setFailureThreshold(1);
        properties.getLlm().setCooldownSeconds(60);
        service = service(model, properties);

        Map<String, Object> failed = service.analyze(Map.of("schema_version", "1.0"));
        Map<String, Object> skipped = service.analyze(Map.of("schema_version", "1.0"));

        assertThat(failed.get("status")).isEqualTo("failed");
        assertThat(String.valueOf(failed.get("error")))
            .contains("[redacted]")
            .doesNotContain("secret-token");
        assertThat(skipped.get("status")).isEqualTo("skipped");
        assertThat(skipped.get("reason")).isEqualTo("llm circuit breaker is open");
        verify(model, times(2)).call(any(Prompt.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "anthropic", "gemini", "ollama"})
    void providerOptionsShareTheSameValidatedContract(String providerName) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response("""
            {
              "summary": "Provider contract",
              "root_cause_candidates": [],
              "action_suggestions": [],
              "additional_checks": []
            }
            """));
        RcaConsoleProperties properties = properties();
        properties.getLlm().setProvider(providerName);
        service = service(model, properties);

        Map<String, Object> result = service.analyze(Map.of("schema_version", "1.0"));

        assertThat(result.get("status")).isEqualTo("completed");
        assertThat(result.get("provider")).isEqualTo(providerName);
    }

    private LlmAnalysisService service(ChatModel model, RcaConsoleProperties properties) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(model));
        return new LlmAnalysisService(provider, new ObjectMapper(), properties);
    }

    private RcaConsoleProperties properties() {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getLlm().setEnabled(true);
        properties.getLlm().setProvider("openai");
        properties.getLlm().setModel("contract-test");
        properties.getLlm().setTimeoutSeconds(2);
        properties.getLlm().setMaxAttempts(1);
        return properties;
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
