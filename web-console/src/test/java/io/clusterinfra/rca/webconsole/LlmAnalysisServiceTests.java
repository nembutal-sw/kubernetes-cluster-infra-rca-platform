package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.LlmTestResponse;
import io.clusterinfra.rca.webconsole.service.LlmAnalysisService;
import io.clusterinfra.rca.webconsole.service.RcaMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

class LlmAnalysisServiceTests {
    private LlmAnalysisService service;
    private SimpleMeterRegistry registry;

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
                {"cause": "inode exhaustion", "confidence": "HIGH", "supporting_evidence_ids": ["ev-inode"]}
              ],
              "action_suggestions": [
                {"action_key": "../../restart", "action": "Inspect storage", "reason": "Confirm the signal"}
              ],
              "additional_checks": ["df -i"],
              "untrusted_extra": "discard me"
            }
            """));
        service = service(model, properties());

        Map<String, Object> result = service.analyze(evidencePayload());

        assertThat(result.get("status")).isEqualTo("completed");
        assertThat(result.get("prompt_version")).isEqualTo("llm-rca-analyzer/v2");
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
            .contains("ev-inode")
            .contains("filesystem.inode_used_percent")
            .contains("manual_investigation")
            .doesNotContain("untrusted_extra");
    }

    @Test
    void rejectsRootCauseCandidateThatReferencesUnknownEvidence() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response("""
            {
              "summary": "Storage pressure",
              "root_cause_candidates": [
                {"cause": "inode exhaustion", "confidence": "high", "supporting_evidence_ids": ["ev-invented"]}
              ],
              "action_suggestions": [],
              "additional_checks": []
            }
            """));
        service = service(model, properties());

        Map<String, Object> result = service.analyze(evidencePayload());

        assertThat(result.get("status")).isEqualTo("failed");
        assertThat(String.valueOf(result.get("error")))
            .contains("LlmResponseValidationException")
            .contains("unknown evidence ID: ev-invented");
    }

    @Test
    void rejectsLegacyFreeFormEvidenceWithoutCatalogReference() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response("""
            {
              "summary": "Storage pressure",
              "root_cause_candidates": [
                {"cause": "inode exhaustion", "confidence": "high", "supporting_evidence": ["inode=98%"]}
              ],
              "action_suggestions": [],
              "additional_checks": []
            }
            """));
        service = service(model, properties());

        Map<String, Object> result = service.analyze(evidencePayload());

        assertThat(result.get("status")).isEqualTo("failed");
        assertThat(String.valueOf(result.get("error")))
            .contains("free-form supporting_evidence is not allowed")
            .contains("supporting_evidence_ids must be a list");
    }

    @Test
    void recordsProviderUsageAndConfiguredCostEstimate() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(responseWithUsage("""
            {
              "summary": "Storage pressure",
              "root_cause_candidates": [],
              "action_suggestions": [],
              "additional_checks": []
            }
            """, 1000, 500, 1500));
        RcaConsoleProperties properties = properties();
        properties.getLlm().setInputCostPerMillionTokens(2.5);
        properties.getLlm().setOutputCostPerMillionTokens(10.0);
        service = service(model, properties);

        Map<String, Object> result = service.analyze(evidencePayload());

        assertThat(result.get("status")).isEqualTo("completed");
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) result.get("usage");
        assertThat(usage)
            .containsEntry("usage_available", true)
            .containsEntry("input_tokens", 1000)
            .containsEntry("output_tokens", 500)
            .containsEntry("total_tokens", 1500);
        assertThat(String.valueOf(usage.get("estimated_cost_usd"))).isEqualTo("0.00750000");
        assertThat(registry.get("rca.llm.tokens")
            .tag("operation", "analysis")
            .tag("provider", "openai")
            .tag("model", "contract-test")
            .tag("type", "input")
            .counter().count()).isEqualTo(1000);
        assertThat(registry.get("rca.llm.estimated.cost.usd")
            .tag("operation", "analysis")
            .tag("provider", "openai")
            .tag("model", "contract-test")
            .counter().count()).isEqualTo(0.0075);
    }

    @Test
    void aggregatesUsageAcrossSchemaRetryAttempts() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(
            responseWithUsage("""
                {
                  "summary": [],
                  "root_cause_candidates": [],
                  "action_suggestions": [],
                  "additional_checks": []
                }
                """, 100, 20, 120),
            responseWithUsage("""
                {
                  "summary": "Validated on retry",
                  "root_cause_candidates": [],
                  "action_suggestions": [],
                  "additional_checks": []
                }
                """, 200, 30, 230)
        );
        RcaConsoleProperties properties = properties();
        properties.getLlm().setMaxAttempts(2);
        service = service(model, properties);

        Map<String, Object> result = service.analyze(evidencePayload());

        assertThat(result.get("status")).isEqualTo("completed");
        assertThat(result.get("attempts")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) result.get("usage");
        assertThat(usage)
            .containsEntry("input_tokens", 300)
            .containsEntry("output_tokens", 50)
            .containsEntry("total_tokens", 350);
        assertThat(registry.get("rca.llm.tokens")
            .tag("operation", "analysis")
            .tag("provider", "openai")
            .tag("model", "contract-test")
            .tag("type", "total")
            .counter().count()).isEqualTo(350);
    }

    @Test
    void extractsJsonObjectFromExplanatoryProviderText() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response("""
            The result is below.

            ```json
            {
              "summary": {
                "most_likely_cause": "kubelet could not report healthy status",
                "confidence": "medium",
                "reasoning": "node condition and kubelet status match"
              },
              "root_cause_candidates": [],
              "action_suggestions": [],
              "additional_checks": ["kubectl describe node <node>"]
            }
            ```
            """));
        service = service(model, properties());

        Map<String, Object> result = service.analyze(Map.of("schema_version", "1.0"));

        assertThat(result.get("status")).isEqualTo("completed");
        @SuppressWarnings("unchecked")
        Map<String, Object> normalized = (Map<String, Object>) result.get("result");
        assertThat(normalized.get("summary").toString())
            .contains("Cause: kubelet could not report healthy status")
            .contains("Confidence: medium")
            .contains("Reasoning: node condition and kubelet status match");
    }

    @Test
    void rejectsSchemaInvalidProviderResponse() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response("""
            {
              "summary": ["not", "a", "summary"],
              "root_cause_candidates": "not-a-list",
              "action_suggestions": [],
              "additional_checks": [{"command": "kubectl get nodes"}]
            }
            """));
        service = service(model, properties());

        Map<String, Object> result = service.analyze(Map.of("schema_version", "1.0"));

        assertThat(result.get("status")).isEqualTo("failed");
        assertThat(String.valueOf(result.get("error")))
            .contains("LlmResponseValidationException")
            .contains("summary must be a string or object")
            .contains("root_cause_candidates must be a list")
            .contains("additional_checks[0] must be a string");
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

    @Test
    void exposesRedactedProviderRootCauseThroughAsyncWrappers() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException(
            "provider request failed",
            new IllegalStateException("401 invalid authentication; authorization=secret-token")
        ));
        service = service(model, properties());

        Map<String, Object> result = service.analyze(Map.of("schema_version", "1.0"));

        assertThat(result.get("status")).isEqualTo("failed");
        assertThat(String.valueOf(result.get("error")))
            .contains("401 invalid authentication")
            .contains("[redacted]")
            .doesNotContain("secret-token");
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

    @Test
    void testConnectionUsesChatModelWithoutReturningProviderContent() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response("ok"));
        service = service(model, properties());

        LlmTestResponse response = service.testConnection();

        assertThat(response.outcome()).isEqualTo("completed");
        assertThat(response.promptVersion()).isEqualTo("llm-connectivity-test/v1");
        assertThat(response.provider()).isEqualTo("openai");
        assertThat(response.model()).isEqualTo("contract-test");
        assertThat(response.responseChars()).isEqualTo(2);
        assertThat(response.error()).isBlank();
        verify(model).call(any(Prompt.class));
    }

    @Test
    void testConnectionSkipsWhenAnalyzerIsDisabled() {
        ChatModel model = mock(ChatModel.class);
        RcaConsoleProperties properties = properties();
        properties.getLlm().setEnabled(false);
        service = service(model, properties);

        LlmTestResponse response = service.testConnection();

        assertThat(response.outcome()).isEqualTo("skipped");
        assertThat(response.message()).contains("disabled");
        verify(model, times(0)).call(any(Prompt.class));
    }

    private LlmAnalysisService service(ChatModel model, RcaConsoleProperties properties) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(model));
        registry = new SimpleMeterRegistry();
        return new LlmAnalysisService(
            provider,
            new ObjectMapper(),
            properties,
            new RcaMetrics(registry)
        );
    }

    private Map<String, Object> evidencePayload() {
        return Map.of(
            "schema_version", "1.0",
            "evidence_catalog", List.of(Map.of(
                "evidence_id", "ev-inode",
                "signal", "inode_exhaustion",
                "component", "disk",
                "interpretation", "inode usage is above the critical threshold",
                "evidence_paths", List.of("filesystem.inode_used_percent")
            ))
        );
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

    private ChatResponse responseWithUsage(String content, int input, int output, int total) {
        return new ChatResponse(
            List.of(new Generation(new AssistantMessage(content))),
            ChatResponseMetadata.builder().usage(new DefaultUsage(input, output, total)).build()
        );
    }
}
