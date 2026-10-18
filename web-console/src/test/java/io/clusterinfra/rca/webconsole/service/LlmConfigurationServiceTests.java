package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class LlmConfigurationServiceTests {
    @ParameterizedTest
    @CsvSource({
        "spring.ai.openai.api-key,spring.ai.openai.base-url",
        "spring.ai.openai-sdk.api-key,spring.ai.openai-sdk.base-url",
        "SPRING_AI_OPENAI_API_KEY,SPRING_AI_OPENAI_BASE_URL",
        "SPRING_AI_OPENAI_SDK_API_KEY,SPRING_AI_OPENAI_SDK_BASE_URL",
        "RCA_LLM_API_KEY,RCA_LLM_BASE_URL"
    })
    void configuredCurrentLegacyAndGenericAliasesReportReadyWithoutRevealingValues(String keyProperty, String urlProperty) {
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues(
                "spring.ai.model.chat=openai",
                keyProperty + "=diagnostics-test-key",
                urlProperty + "=http://127.0.0.1:12345/v1"
            )
            .run(context -> {
                LlmConfigurationService service = new LlmConfigurationService(properties(), context.getEnvironment());

                assertThat(service.info().credentialConfigured()).isTrue();
                assertThat(service.info().baseUrlConfigured()).isTrue();
                assertThat(service.info().credentialProperty()).isEqualTo("spring.ai.openai.api-key");
                assertThat(service.info().credentialEnv()).isEqualTo("SPRING_AI_OPENAI_API_KEY");
                assertThat(service.info().baseUrlProperty()).isEqualTo("spring.ai.openai.base-url");
                assertThat(service.info().baseUrlEnv()).isEqualTo("SPRING_AI_OPENAI_BASE_URL");
                assertThat(service.diagnostics().outcome()).isEqualTo("ready");
                assertThat(service.diagnostics().toString())
                    .doesNotContain("diagnostics-test-key")
                    .doesNotContain("http://127.0.0.1:12345/v1");
            });
    }

    @Test
    void missingCurrentAndLegacyValuesStillRequireAction() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.model.chat", "openai")
            .withProperty("spring.ai.openai.api-key", " ")
            .withProperty("spring.ai.openai.base-url", " ");
        LlmConfigurationService service = new LlmConfigurationService(properties(), environment);

        assertThat(service.info().credentialConfigured()).isFalse();
        assertThat(service.info().baseUrlConfigured()).isFalse();
        assertThat(service.diagnostics().outcome()).isEqualTo("action_required");
        assertThat(service.diagnostics().checks()).anySatisfy(check -> {
            assertThat(check.key()).isEqualTo("credential");
            assertThat(check.status()).isEqualTo("fail");
            assertThat(check.remediation()).contains("SPRING_AI_OPENAI_API_KEY");
        }).anySatisfy(check -> {
            assertThat(check.key()).isEqualTo("base_url");
            assertThat(check.status()).isEqualTo("fail");
            assertThat(check.remediation()).contains("SPRING_AI_OPENAI_BASE_URL");
        });
    }

    @Test
    void setupGuideUsesUnifiedStarterAndDocumentsLegacyAliases() {
        LlmConfigurationService service = new LlmConfigurationService(properties(), new MockEnvironment());

        assertThat(service.setupGuide().providers()).filteredOn(option -> "openai_compatible".equals(option.provider()))
            .singleElement().satisfies(option -> {
                assertThat(option.springAiChatModel()).isEqualTo("openai");
                assertThat(option.credentialEnv()).isEqualTo("SPRING_AI_OPENAI_API_KEY");
                assertThat(option.baseUrlEnv()).isEqualTo("SPRING_AI_OPENAI_BASE_URL");
                assertThat(option.note()).contains("SPRING_AI_OPENAI_SDK_API_KEY", "SPRING_AI_OPENAI_SDK_BASE_URL");
            });
    }

    private RcaConsoleProperties properties() {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getLlm().setEnabled(true);
        properties.getLlm().setProvider("openai_compatible");
        properties.getLlm().setModel("diagnostics-test-model");
        return properties;
    }
}
