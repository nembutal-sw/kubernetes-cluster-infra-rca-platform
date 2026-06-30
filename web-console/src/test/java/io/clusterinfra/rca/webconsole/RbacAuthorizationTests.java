package io.clusterinfra.rca.webconsole;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserRole;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RbacAuthorizationTests {
    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
            "jdbc:h2:mem:rbac-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("spring.ai.model.chat", () -> "none");
        registry.add("rca.pipeline.initial-delay-ms", () -> "600000");
    }

    @Test
    void viewerCannotCallMutationApi() throws Exception {
        mockMvc.perform(post("/api/clusters")
                .with(authentication(user(UserRole.viewer)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"forbidden-cluster","environment":"test"}
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    void onlyAdminOrAuditorCanReadAuditLog() throws Exception {
        mockMvc.perform(get("/api/audit/events").with(authentication(user(UserRole.auditor))))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/audit/events").with(authentication(user(UserRole.viewer))))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit/events/export?format=json")
                .with(authentication(user(UserRole.auditor))))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/audit/events/export?format=json")
                .with(authentication(user(UserRole.viewer))))
            .andExpect(status().isForbidden());
    }

    @Test
    void onlyAdminOrApproverCanCallApprovalApi() throws Exception {
        String body = """
            {"confirmed":true,"note":"authorization test"}
            """;
        mockMvc.perform(get("/api/rca/action-requests").with(authentication(user(UserRole.approver))))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/rca/action-requests/missing/approve")
                .with(authentication(user(UserRole.approver)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/rca/action-requests/missing/approve")
                .with(authentication(user(UserRole.operator)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());
    }

    @Test
    void viewerAndApproverCannotExportReportsOrEvidenceBundles() throws Exception {
        for (UserRole role : List.of(UserRole.viewer, UserRole.approver)) {
            mockMvc.perform(get("/api/rca/reports/export")
                    .with(authentication(user(role))))
                .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/rca/reports/missing/export")
                    .with(authentication(user(role))))
                .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/rca/reports/missing/bundle")
                    .with(authentication(user(role))))
                .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/rca/reports/missing/bundle/manifest")
                    .with(authentication(user(role))))
                .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/rca/action-executions")
                    .with(authentication(user(role))))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void operatorCanMarkApprovedManualActionCompleteButApproverCannot() throws Exception {
        String body = """
            {"confirmed":true,"note":"handled through external runbook"}
            """;
        mockMvc.perform(post("/api/rca/action-requests/missing/complete-manual")
                .with(authentication(user(UserRole.operator)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/rca/action-requests/missing/complete-manual")
                .with(authentication(user(UserRole.approver)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());
    }

    @Test
    void actuatorMetricsRequireOperationalRole() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics")
                .with(authentication(user(UserRole.viewer))))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics")
                .with(authentication(user(UserRole.operator))))
            .andExpect(status().isOk());
    }

    private Authentication user(UserRole role) {
        UserAccount account = new UserAccount(
            "user-" + role.name(),
            role.name() + "@example.com",
            role.name(),
            role,
            role,
            UserStatus.active,
            null,
            null,
            "system",
            Instant.now(),
            Instant.now()
        );
        return new UsernamePasswordAuthenticationToken(
            account,
            "test",
            List.of(new SimpleGrantedAuthority("ROLE_" + role.name().toUpperCase()))
        );
    }
}
