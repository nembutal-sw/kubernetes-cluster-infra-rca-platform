package io.clusterinfra.rca.webconsole.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentIdentity;
import io.clusterinfra.rca.webconsole.service.AuditService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class AgentAuthenticationFilterTests {
    @Mock
    private AccessService access;

    @Mock
    private AuditService audit;

    private AgentAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AgentAuthenticationFilter(access, audit, new ObjectMapper());
    }

    @Test
    void kubernetesEnrollmentRequiresBearerOnlyAndPublishesTrustedIdentity() throws Exception {
        AgentEnrollmentIdentity identity = new AgentEnrollmentIdentity(
            "kubernetes_token_review",
            "system:serviceaccount:rca-system:cluster-infra-rca-agent",
            "sa-uid",
            "rca-system",
            "cluster-infra-rca-agent",
            "agent-pod",
            "pod-uid"
        );
        when(access.verifyAgentEnrollment(
            "cluster-1",
            "worker-1",
            "kubernetes-token-review",
            "projected-token"
        )).thenReturn(identity);
        MockHttpServletRequest request = registrationRequest(false);
        request.addHeader("Authorization", "Bearer projected-token");
        request.addHeader("X-RCA-Agent-Enrollment", "kubernetes-token-review");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(AgentAuthenticationFilter.ENROLLMENT_IDENTITY_ATTRIBUTE))
            .isEqualTo(identity);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void kubernetesEnrollmentRejectsLegacyBodyCredentialEvenWhenItMatchesBearer() throws Exception {
        MockHttpServletRequest request = registrationRequest(true);
        request.addHeader("Authorization", "Bearer projected-token");
        request.addHeader("X-RCA-Agent-Enrollment", "kubernetes-token-review");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("legacy body credentials");
        verify(access, never()).verifyAgentEnrollment(any(), any(), any(), any());
    }

    @Test
    void registrationWithoutEnrollmentHeaderRetainsBootstrapCompatibility() throws Exception {
        when(access.verifyAgentEnrollment("cluster-1", "worker-1", "bootstrap-token", "bootstrap-token"))
            .thenReturn(new AgentEnrollmentIdentity("bootstrap_token", null, null, null, null, null, null));
        MockHttpServletRequest request = registrationRequest(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        verify(access).verifyAgentEnrollment(
            eq("cluster-1"),
            eq("worker-1"),
            eq("bootstrap-token"),
            eq("bootstrap-token")
        );
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest registrationRequest(boolean includeLegacyToken) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agents/register");
        request.setRequestURI("/api/agents/register");
        request.setContentType("application/json");
        String token = includeLegacyToken ? ",\"agent_token\":\"bootstrap-token\"" : "";
        request.setContent(("{\"cluster_id\":\"cluster-1\",\"node_name\":\"worker-1\"" + token + "}")
            .getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
