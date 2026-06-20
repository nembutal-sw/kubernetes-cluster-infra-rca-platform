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
                .hasStackTraceContaining("RCA_DEFAULT_ADMIN_PASSWORD")
                .hasStackTraceContaining("RCA_WEBHOOK_TOKEN")
                .hasStackTraceContaining("RCA_DB_PASSWORD")
                .hasStackTraceContaining("RCA_PUBLIC_API_BASE_URL")
                .hasStackTraceContaining("RCA_ENCRYPTION_SECRET");
        });
    }

    @Test
    void safeProductionConfigurationLoads() {
        contextRunner
            .withPropertyValues(
                "rca.default-admin-password=a-strong-admin-password",
                "rca.webhook-token=a-strong-webhook-token",
                "spring.datasource.password=a-strong-database-password",
                "rca.session-ttl-hours=12",
                "rca.public-api-base-url=https://rca.example.com",
                "rca.audit.enabled=true",
                "rca.demo.enabled=false",
                "rca.security.encryption-secret=a-strong-encryption-secret",
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
                "rca.default-admin-password=a-strong-admin-password",
                "rca.webhook-token=a-strong-webhook-token",
                "spring.datasource.password=a-strong-database-password",
                "rca.public-api-base-url=https://rca.example.com",
                "rca.security.encryption-secret=a-strong-encryption-secret",
                "rca.llm.enabled=true",
                "rca.llm.provider=openai",
                "rca.llm.model=gpt-test",
                "spring.ai.model.chat=openai-sdk"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("spring.ai.openai-sdk.api-key");
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
