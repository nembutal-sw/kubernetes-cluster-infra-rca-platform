package io.clusterinfra.rca.webconsole.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Keeps the existing OpenAI SDK configuration working with Spring AI 2's unified starter. */
public final class SpringAiCompatibilityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> aliases = new LinkedHashMap<>();
        if ("openai-sdk".equals(environment.getProperty("spring.ai.model.chat"))) {
            aliases.put("spring.ai.model.chat", "openai");
        }
        copyLegacyProperty(environment, aliases, "api-key");
        copyLegacyProperty(environment, aliases, "base-url");
        if (!aliases.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("rcaSpringAiCompatibility", aliases));
        }
    }

    private void copyLegacyProperty(ConfigurableEnvironment environment, Map<String, Object> aliases, String suffix) {
        String current = "spring.ai.openai." + suffix;
        String legacy = environment.getProperty("spring.ai.openai-sdk." + suffix, "");
        if (environment.getProperty(current, "").isBlank() && !legacy.isBlank()) {
            aliases.put(current, legacy);
        }
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
