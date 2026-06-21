package io.clusterinfra.rca.webconsole.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import java.security.cert.X509Certificate;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AgentMtlsFilterTests {
    @Test
    void requiredClientCertificateRejectsMissingCertificate() throws Exception {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getSecurity().setAgentMtlsRequired(true);
        AgentMtlsFilter filter = new AgentMtlsFilter(properties, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest(
            "POST",
            "/api/agents/heartbeat"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("client certificate is required");
    }

    @Test
    void acceptedClientCertificateReachesAgentAuthenticationChain() throws Exception {
        RcaConsoleProperties properties = new RcaConsoleProperties();
        properties.getSecurity().setAgentMtlsRequired(true);
        AgentMtlsFilter filter = new AgentMtlsFilter(properties, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest(
            "POST",
            "/api/agents/heartbeat"
        );
        X509Certificate certificate = mock(X509Certificate.class);
        X500Principal principal = new X500Principal("CN=worker-a");
        when(certificate.getSubjectX500Principal()).thenReturn(principal);
        request.setAttribute(
            "jakarta.servlet.request.X509Certificate",
            new X509Certificate[] {certificate}
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute("rca.agent_certificate_subject"))
            .isEqualTo("CN=worker-a");
    }
}
