package io.clusterinfra.rca.webconsole.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.clusterinfra.rca.webconsole.service.LlmAnalysisService;
import io.clusterinfra.rca.webconsole.service.RcaMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class SpringAiProviderWiringTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(
            ToolCallingAutoConfiguration.class,
            OpenAiChatAutoConfiguration.class,
            AnthropicChatAutoConfiguration.class,
            GoogleGenAiChatAutoConfiguration.class,
            OllamaApiAutoConfiguration.class,
            OllamaChatAutoConfiguration.class
        ))
        .withInitializer(context -> new SpringAiCompatibilityEnvironmentPostProcessor()
            .postProcessEnvironment(context.getEnvironment(), new SpringApplication()))
        .withPropertyValues(
            "spring.ai.openai.api-key=provider-wiring-test-key",
            "spring.ai.anthropic.api-key=provider-wiring-test-key",
            "spring.ai.google.genai.api-key=provider-wiring-test-key",
            "spring.ai.ollama.init.pull-model-strategy=never"
        );

    @ParameterizedTest
    @CsvSource({
        "openai,OpenAiChatModel",
        "openai-sdk,OpenAiChatModel",
        "anthropic,AnthropicChatModel",
        "google-genai,GoogleGenAiChatModel",
        "ollama,OllamaChatModel"
    })
    void selectedProviderCreatesOnlyItsOwnChatModelWithoutTools(String selector, String modelType) {
        runner.withPropertyValues("spring.ai.model.chat=" + selector).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ChatModel.class);
            ChatModel model = context.getBean(ChatModel.class);
            assertThat(model.getClass().getSimpleName()).isEqualTo(modelType);
            assertThat(model.getOptions()).isInstanceOf(ToolCallingChatOptions.class);
            assertThat(((ToolCallingChatOptions) model.getOptions()).getToolCallbacks()).isNullOrEmpty();
        });
    }

    @Test
    void disabledSelectorCreatesNoCloudModel() {
        runner.withPropertyValues("spring.ai.model.chat=none").run(context ->
            assertThat(context).hasNotFailed().doesNotHaveBean(ChatModel.class));
    }

    @Test
    void legacyDirectPropertiesRemainAvailableToTheUnifiedOpenAiStarter() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.model.chat", "openai-sdk")
            .withProperty("spring.ai.openai-sdk.api-key", "legacy-test-key")
            .withProperty("spring.ai.openai-sdk.base-url", "http://127.0.0.1:12345/v1");

        new SpringAiCompatibilityEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("openai");
        assertThat(environment.getProperty("spring.ai.openai.api-key")).isEqualTo("legacy-test-key");
        assertThat(environment.getProperty("spring.ai.openai.base-url")).isEqualTo("http://127.0.0.1:12345/v1");
    }

    @Test
    void explicitCurrentPropertiesTakePrecedenceOverLegacyAliases() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("configured", Map.of(
            "spring.ai.model.chat", "anthropic",
            "spring.ai.openai.api-key", "current-test-key",
            "spring.ai.openai-sdk.api-key", "legacy-test-key"
        )));

        new SpringAiCompatibilityEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("anthropic");
        assertThat(environment.getProperty("spring.ai.openai.api-key")).isEqualTo("current-test-key");
    }

    @Test
    void unifiedOpenAiSdkSendsBoundedTextOnlyRequestAndParsesRcaResponse() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AtomicReference<JsonNode> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(json.readTree(exchange.getRequestBody()));
            String content = json.writeValueAsString(Map.of(
                "summary", "Stub response", "root_cause_candidates", java.util.List.of(),
                "action_suggestions", java.util.List.of(), "additional_checks", java.util.List.of()
            ));
            byte[] response = json.writeValueAsBytes(Map.of(
                "id", "chatcmpl-migration-test", "object", "chat.completion", "created", 1,
                "model", "migration-test", "choices", java.util.List.of(Map.of(
                    "index", 0, "message", Map.of("role", "assistant", "content", content), "finish_reason", "stop"
                )), "usage", Map.of("prompt_tokens", 2, "completion_tokens", 3, "total_tokens", 5)
            ));
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            runner.withPropertyValues(
                "spring.ai.model.chat=openai-sdk",
                "spring.ai.openai.base-url=http://127.0.0.1:" + server.getAddress().getPort() + "/v1"
            ).run(context -> {
                assertThat(context).hasNotFailed();
                RcaConsoleProperties properties = new RcaConsoleProperties();
                properties.getLlm().setEnabled(true);
                properties.getLlm().setProvider("openai_compatible");
                properties.getLlm().setModel("migration-test");
                properties.getLlm().setMaxOutputTokens(256);
                properties.getLlm().setMaxAttempts(1);
                SimpleMeterRegistry metrics = new SimpleMeterRegistry();
                try {
                    LlmAnalysisService service = new LlmAnalysisService(
                        context.getBeanProvider(ChatModel.class), json, properties, new RcaMetrics(metrics)
                    );
                    try {
                        assertThat(service.analyze(Map.of()).get("status")).isEqualTo("completed");
                        assertThat(request.get().path("model").asText()).isEqualTo("migration-test");
                        assertThat(request.get().path("max_completion_tokens").asInt()).isEqualTo(256);
                        assertThat(request.get().path("messages").size()).isEqualTo(2);
                        assertThat(request.get().path("tools").isMissingNode() || request.get().path("tools").isEmpty())
                            .isTrue();
                    } finally {
                        service.shutdown();
                    }
                } finally {
                    metrics.close();
                }
            });
        } finally {
            server.stop(0);
        }
    }
}
