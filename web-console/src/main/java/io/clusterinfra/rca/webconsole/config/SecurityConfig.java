package io.clusterinfra.rca.webconsole.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.controller.ApiErrorResponse;
import io.clusterinfra.rca.webconsole.security.AgentAuthenticationFilter;
import io.clusterinfra.rca.webconsole.security.AgentMtlsFilter;
import io.clusterinfra.rca.webconsole.security.ManifestAccessFilter;
import io.clusterinfra.rca.webconsole.security.MetricsAuthenticationFilter;
import io.clusterinfra.rca.webconsole.security.PlatformAuthenticationFilter;
import io.clusterinfra.rca.webconsole.security.RequestBodyLimitFilter;
import io.clusterinfra.rca.webconsole.security.SameOriginMutationFilter;
import io.clusterinfra.rca.webconsole.security.WebhookAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        PlatformAuthenticationFilter authenticationFilter,
        RequestBodyLimitFilter requestBodyLimitFilter,
        AgentMtlsFilter agentMtlsFilter,
        AgentAuthenticationFilter agentAuthenticationFilter,
        WebhookAuthenticationFilter webhookAuthenticationFilter,
        ManifestAccessFilter manifestAccessFilter,
        MetricsAuthenticationFilter metricsAuthenticationFilter,
        SameOriginMutationFilter sameOriginMutationFilter,
        ObjectMapper objectMapper
    ) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.disable())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(
                    "/",
                    "/console",
                    "/error",
                    "/favicon.ico",
                    "/assets/**",
                    "/health",
                    "/health/ready",
                    "/actuator/health/**",
                    "/api/auth/login",
                    "/api/agents/register",
                    "/api/agents/heartbeat",
                    "/api/agents/evidence-requests",
                    "/api/agents/evidence-responses",
                    "/api/agents/realtime-events",
                    "/api/agents/action-executions",
                    "/api/agents/action-results",
                    "/api/webhooks/alertmanager",
                    "/api/clusters/*/agent-manifest"
                ).permitAll()
                .requestMatchers("/actuator/metrics/**", "/actuator/prometheus")
                    .hasAnyRole("ADMIN", "OPERATOR", "AUDITOR", "METRICS")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) -> {
                    ApiErrorResponse.write(
                        objectMapper,
                        request,
                        response,
                        HttpStatus.UNAUTHORIZED.value(),
                        "authentication_required",
                        "login required"
                    );
                })
                .accessDeniedHandler((request, response, exception) -> {
                    ApiErrorResponse.write(
                        objectMapper,
                        request,
                        response,
                        HttpStatus.FORBIDDEN.value(),
                        "access_denied",
                        "insufficient role"
                    );
                })
            )
            .addFilterBefore(authenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(requestBodyLimitFilter, PlatformAuthenticationFilter.class)
            .addFilterAfter(agentMtlsFilter, PlatformAuthenticationFilter.class)
            .addFilterAfter(agentAuthenticationFilter, AgentMtlsFilter.class)
            .addFilterAfter(webhookAuthenticationFilter, AgentAuthenticationFilter.class)
            .addFilterAfter(manifestAccessFilter, WebhookAuthenticationFilter.class)
            .addFilterAfter(metricsAuthenticationFilter, ManifestAccessFilter.class)
            .addFilterAfter(sameOriginMutationFilter, MetricsAuthenticationFilter.class)
            .build();
    }

    @Bean
    FilterRegistrationBean<PlatformAuthenticationFilter> platformAuthenticationRegistration(
        PlatformAuthenticationFilter filter
    ) {
        return securityOnly(filter);
    }

    @Bean
    FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitRegistration(
        RequestBodyLimitFilter filter
    ) {
        return securityOnly(filter);
    }

    @Bean
    FilterRegistrationBean<AgentMtlsFilter> agentMtlsRegistration(AgentMtlsFilter filter) {
        return securityOnly(filter);
    }

    @Bean
    FilterRegistrationBean<AgentAuthenticationFilter> agentAuthenticationRegistration(
        AgentAuthenticationFilter filter
    ) {
        return securityOnly(filter);
    }

    @Bean
    FilterRegistrationBean<WebhookAuthenticationFilter> webhookAuthenticationRegistration(
        WebhookAuthenticationFilter filter
    ) {
        return securityOnly(filter);
    }

    @Bean
    FilterRegistrationBean<ManifestAccessFilter> manifestAccessRegistration(ManifestAccessFilter filter) {
        return securityOnly(filter);
    }

    @Bean
    FilterRegistrationBean<MetricsAuthenticationFilter> metricsAuthenticationRegistration(
        MetricsAuthenticationFilter filter
    ) {
        return securityOnly(filter);
    }

    @Bean
    FilterRegistrationBean<SameOriginMutationFilter> sameOriginMutationRegistration(
        SameOriginMutationFilter filter
    ) {
        return securityOnly(filter);
    }

    private <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> securityOnly(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
