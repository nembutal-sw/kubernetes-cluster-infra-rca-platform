package io.clusterinfra.rca.webconsole.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

class ProductionSecurityValidatorTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context ->
            ((ConfigurableEnvironment) context.getEnvironment()).setActiveProfiles("prod")
        )
        .withUserConfiguration(ValidatorConfiguration.class);

    @Test
    void unsafeProductionDefaultsFailContextStartup() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasStackTraceContaining("Unsafe production configuration")
                .hasStackTraceContaining("RCA_DEFAULT_ADMIN_USERNAME")
                .hasStackTraceContaining("RCA_DEFAULT_ADMIN_PASSWORD")
                .hasStackTraceContaining("RCA_WEBHOOK_TOKEN")
                .hasStackTraceContaining("RCA_DB_PASSWORD")
                .hasStackTraceContaining("RCA_PUBLIC_API_BASE_URL")
                .hasStackTraceContaining("RCA_ENCRYPTION_SECRET")
                .hasStackTraceContaining("RCA_METRICS_TOKEN");
        });
    }

    @Test
    void safeProductionConfigurationLoads() {
        contextRunner
            .withPropertyValues(
                "rca.default-admin-username=platform-admin",
                "rca.default-admin-password=a-strong-admin-password",
                "rca.webhook-token=a-strong-webhook-token",
                "spring.datasource.password=a-strong-database-password",
                "rca.session-ttl-hours=12",
                "rca.public-api-base-url=https://rca.example.com",
                "rca.audit.enabled=true",
                "rca.demo.enabled=false",
                "rca.security.encryption-secret=a-strong-encryption-secret",
                "rca.observability.metrics-token=a-strong-metrics-token",
                "rca.llm.enabled=false"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ProductionSecurityValidator.class);
            });
    }

    @Test
    void enabledLlmRequiresProviderModelChatModelAndCredential() {
        contextRunner
            .withPropertyValues(
                "rca.default-admin-username=platform-admin",
                "rca.default-admin-password=a-strong-admin-password",
                "rca.webhook-token=a-strong-webhook-token",
                "spring.datasource.password=a-strong-database-password",
                "rca.public-api-base-url=https://rca.example.com",
                "rca.security.encryption-secret=a-strong-encryption-secret",
                "rca.observability.metrics-token=a-strong-metrics-token",
                "rca.llm.enabled=true",
                "rca.llm.provider=openai",
                "rca.llm.model=gpt-test",
                "spring.ai.model.chat=openai-sdk"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("spring.ai.openai-sdk.api-key or RCA_LLM_API_KEY");
            });
    }

    @Test
    void enabledLlmAcceptsGenericApiKeyAlias() {
        contextRunner
            .withPropertyValues(
                "rca.default-admin-username=platform-admin",
                "rca.default-admin-password=a-strong-admin-password",
                "rca.webhook-token=a-strong-webhook-token",
                "spring.datasource.password=a-strong-database-password",
                "rca.public-api-base-url=https://rca.example.com",
                "rca.security.encryption-secret=a-strong-encryption-secret",
                "rca.observability.metrics-token=a-strong-metrics-token",
                "rca.llm.enabled=true",
                "rca.llm.provider=openai",
                "rca.llm.model=gpt-test",
                "spring.ai.model.chat=openai-sdk",
                "RCA_LLM_API_KEY=test-api-key"
            )
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void openAiCompatibleProviderRequiresBaseUrl() {
        contextRunner
            .withPropertyValues(
                "rca.default-admin-username=platform-admin",
                "rca.default-admin-password=a-strong-admin-password",
                "rca.webhook-token=a-strong-webhook-token",
                "spring.datasource.password=a-strong-database-password",
                "rca.public-api-base-url=https://rca.example.com",
                "rca.security.encryption-secret=a-strong-encryption-secret",
                "rca.observability.metrics-token=a-strong-metrics-token",
                "rca.llm.enabled=true",
                "rca.llm.provider=openai_compatible",
                "rca.llm.model=local-model",
                "spring.ai.model.chat=openai-sdk",
                "RCA_LLM_API_KEY=test-api-key"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("RCA_LLM_BASE_URL is required");
            });
    }

    @Test
    void selfHostedLlmCanLoadWithoutCloudCredential() {
        contextRunner
            .withPropertyValues(
                "rca.default-admin-username=platform-admin",
                "rca.default-admin-password=a-strong-admin-password",
                "rca.webhook-token=a-strong-webhook-token",
                "spring.datasource.password=a-strong-database-password",
                "rca.public-api-base-url=https://rca.example.com",
                "rca.security.encryption-secret=a-strong-encryption-secret",
                "rca.observability.metrics-token=a-strong-metrics-token",
                "rca.llm.enabled=true",
                "rca.llm.provider=self_hosted",
                "rca.llm.model=local-rca-model",
                "spring.ai.model.chat=openai-sdk",
                "spring.ai.openai-sdk.base-url=http://localhost:11434/v1"
            )
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void selfHostedLlmRequiresBaseUrl() {
        contextRunner
            .withPropertyValues(
                "rca.default-admin-username=platform-admin",
                "rca.default-admin-password=a-strong-admin-password",
                "rca.webhook-token=a-strong-webhook-token",
                "spring.datasource.password=a-strong-database-password",
                "rca.public-api-base-url=https://rca.example.com",
                "rca.security.encryption-secret=a-strong-encryption-secret",
                "rca.observability.metrics-token=a-strong-metrics-token",
                "rca.llm.enabled=true",
                "rca.llm.provider=self_hosted",
                "rca.llm.model=local-rca-model",
                "spring.ai.model.chat=openai-sdk"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("spring.ai.openai-sdk.base-url");
            });
    }

    @Test
    void enabledNotificationRequiresDeliveryTarget() {
        contextRunner
            .withPropertyValues(
                "rca.default-admin-username=platform-admin",
                "rca.default-admin-password=a-strong-admin-password",
                "rca.webhook-token=a-strong-webhook-token",
                "spring.datasource.password=a-strong-database-password",
                "rca.public-api-base-url=https://rca.example.com",
                "rca.security.encryption-secret=a-strong-encryption-secret",
                "rca.observability.metrics-token=a-strong-metrics-token",
                "rca.llm.enabled=false",
                "rca.notification.enabled=true"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("RCA_SLACK_WEBHOOK_URL or RCA_NOTIFICATION_WEBHOOK_URL");
            });
    }

    @Test
    void genericNotificationWebhookCanSatisfyProductionDeliveryTarget() {
        contextRunner
            .withPropertyValues(
                "rca.default-admin-username=platform-admin",
                "rca.default-admin-password=a-strong-admin-password",
                "rca.webhook-token=a-strong-webhook-token",
                "spring.datasource.password=a-strong-database-password",
                "rca.public-api-base-url=https://rca.example.com",
                "rca.security.encryption-secret=a-strong-encryption-secret",
                "rca.observability.metrics-token=a-strong-metrics-token",
                "rca.llm.enabled=false",
                "rca.notification.enabled=true",
                "rca.notification.webhook-url=https://siem.example.com/rca",
                "rca.notification.webhook-token=a-strong-notification-token"
            )
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RcaConsoleProperties.class)
    static class ValidatorConfiguration {
        @Bean
        ProductionSecurityValidator productionSecurityValidator(
            Environment environment,
            RcaConsoleProperties properties
        ) {
            return new ProductionSecurityValidator(environment, properties);
        }
    }
}
