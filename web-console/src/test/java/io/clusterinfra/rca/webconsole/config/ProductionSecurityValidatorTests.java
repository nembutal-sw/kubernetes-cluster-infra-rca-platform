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
        .withUserConfiguration(ValidatorConfiguration.class)
        .withPropertyValues(
            "rca.security.opaque-token-pepper=a-distinct-production-opaque-token-pepper"
        );

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
    void productionRejectsMissingOrReusedOpaqueTokenPepper() {
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
                "rca.security.opaque-token-pepper=development-only-opaque-token-pepper",
                "rca.observability.metrics-token=a-strong-metrics-token",
                "rca.llm.enabled=false"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("RCA_OPAQUE_TOKEN_PEPPER must be a non-default secret");
            });

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
                "rca.security.encryption-secret=same-secret-value-with-at-least-32-characters",
                "rca.security.opaque-token-pepper=same-secret-value-with-at-least-32-characters",
                "rca.observability.metrics-token=a-strong-metrics-token",
                "rca.llm.enabled=false"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining(
                        "RCA_OPAQUE_TOKEN_PEPPER must be different from RCA_ENCRYPTION_SECRET"
                    );
            });
    }

    @Test
    void negativeLlmTokenPriceFailsContextStartup() {
        contextRunner
            .withPropertyValues(
                "rca.default-admin-username=platform-admin",
                "rca.default-admin-password=a-strong-admin-password",
                "rca.webhook-token=a-strong-webhook-token",
                "spring.datasource.password=a-strong-database-password",
                "rca.public-api-base-url=https://rca.example.com",
                "rca.security.encryption-secret=a-strong-encryption-secret",
                "rca.observability.metrics-token=a-strong-metrics-token",
                "rca.llm.input-cost-per-million-tokens=-1"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("RCA_LLM_INPUT_COST_PER_MILLION_TOKENS must be a non-negative number");
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
    void enabledLlmRejectsExcessiveProviderRetryAttempts() {
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
                "spring.ai.retry.max-attempts=10",
                "RCA_LLM_API_KEY=test-api-key"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("RCA_SPRING_AI_RETRY_MAX_ATTEMPTS must be between 1 and 3");
            });
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

    @Test
    void notificationOutboxRejectsUnsafeWorkerBounds() {
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
                "rca.notification.max-attempts=0",
                "rca.notification.batch-size=1000",
                "rca.notification.retry-base-seconds=60",
                "rca.notification.retry-max-seconds=10"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("RCA_NOTIFICATION_MAX_ATTEMPTS")
                    .hasStackTraceContaining("RCA_NOTIFICATION_BATCH_SIZE")
                    .hasStackTraceContaining("RCA_NOTIFICATION_RETRY_SECONDS");
            });
    }

    @Test
    void enabledGitOpsRequiresRepositoryAndSecrets() {
        contextRunner
            .withPropertyValues(
                "rca.default-admin-username=platform-admin",
                "rca.default-admin-password=a-strong-admin-password",
                "rca.webhook-token=a-strong-webhook-token",
                "spring.datasource.password=a-strong-database-password",
                "rca.public-api-base-url=https://rca.example.com",
                "rca.security.encryption-secret=a-strong-encryption-secret",
                "rca.observability.metrics-token=a-strong-metrics-token",
                "rca.gitops.enabled=true"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("RCA_GITOPS_REPOSITORY")
                    .hasStackTraceContaining("RCA_GITOPS_TOKEN")
                    .hasStackTraceContaining("RCA_GITOPS_WEBHOOK_SECRET");
            });
    }

    @Test
    void safeGitOpsConfigurationLoads() {
        contextRunner
            .withPropertyValues(
                "rca.default-admin-username=platform-admin",
                "rca.default-admin-password=a-strong-admin-password",
                "rca.webhook-token=a-strong-webhook-token",
                "spring.datasource.password=a-strong-database-password",
                "rca.public-api-base-url=https://rca.example.com",
                "rca.security.encryption-secret=a-strong-encryption-secret",
                "rca.observability.metrics-token=a-strong-metrics-token",
                "rca.gitops.enabled=true",
                "rca.gitops.repository=acme/rca-config",
                "rca.gitops.token=a-strong-github-token",
                "rca.gitops.webhook-secret=a-strong-github-webhook-secret"
            )
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void safeGitLabConfigurationWithNestedGroupLoads() {
        contextRunner
            .withPropertyValues(
                "rca.default-admin-username=platform-admin",
                "rca.default-admin-password=a-strong-admin-password",
                "rca.webhook-token=a-strong-webhook-token",
                "spring.datasource.password=a-strong-database-password",
                "rca.public-api-base-url=https://rca.example.com",
                "rca.security.encryption-secret=a-strong-encryption-secret",
                "rca.observability.metrics-token=a-strong-metrics-token",
                "rca.gitops.enabled=true",
                "rca.gitops.provider=gitlab",
                "rca.gitops.repository=acme/platform/rca-config",
                "rca.gitops.token=a-strong-gitlab-token",
                "rca.gitops.webhook-secret=a-strong-gitlab-webhook-secret"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                RcaConsoleProperties properties = context.getBean(RcaConsoleProperties.class);
                assertThat(properties.getGitOps().getApiBaseUrl()).isEqualTo("https://gitlab.com/api/v4");
            });
    }

    @Test
    void githubConfigurationRejectsNestedRepositoryPath() {
        contextRunner
            .withPropertyValues(
                "rca.default-admin-username=platform-admin",
                "rca.default-admin-password=a-strong-admin-password",
                "rca.webhook-token=a-strong-webhook-token",
                "spring.datasource.password=a-strong-database-password",
                "rca.public-api-base-url=https://rca.example.com",
                "rca.security.encryption-secret=a-strong-encryption-secret",
                "rca.observability.metrics-token=a-strong-metrics-token",
                "rca.gitops.enabled=true",
                "rca.gitops.provider=github",
                "rca.gitops.repository=acme/platform/rca-config",
                "rca.gitops.token=a-strong-github-token",
                "rca.gitops.webhook-secret=a-strong-github-webhook-secret"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("RCA_GITOPS_REPOSITORY is invalid");
            });
    }

    @Test
    void safeGiteaConfigurationLoads() {
        contextRunner
            .withPropertyValues(
                "rca.default-admin-username=platform-admin",
                "rca.default-admin-password=a-strong-admin-password",
                "rca.webhook-token=a-strong-webhook-token",
                "spring.datasource.password=a-strong-database-password",
                "rca.public-api-base-url=https://rca.example.com",
                "rca.security.encryption-secret=a-strong-encryption-secret",
                "rca.observability.metrics-token=a-strong-metrics-token",
                "rca.gitops.enabled=true",
                "rca.gitops.provider=gitea",
                "rca.gitops.api-base-url=https://git.example.com/api/v1",
                "rca.gitops.repository=acme/rca-config",
                "rca.gitops.token=a-strong-gitea-token",
                "rca.gitops.webhook-secret=a-strong-gitea-webhook-secret"
            )
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void giteaConfigurationRequiresExplicitApiBaseUrl() {
        contextRunner
            .withPropertyValues(
                "rca.default-admin-username=platform-admin",
                "rca.default-admin-password=a-strong-admin-password",
                "rca.webhook-token=a-strong-webhook-token",
                "spring.datasource.password=a-strong-database-password",
                "rca.public-api-base-url=https://rca.example.com",
                "rca.security.encryption-secret=a-strong-encryption-secret",
                "rca.observability.metrics-token=a-strong-metrics-token",
                "rca.gitops.enabled=true",
                "rca.gitops.provider=gitea",
                "rca.gitops.repository=acme/rca-config",
                "rca.gitops.token=a-strong-gitea-token",
                "rca.gitops.webhook-secret=a-strong-gitea-webhook-secret"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("RCA_GITOPS_API_BASE_URL is required for gitea");
            });
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
