package io.clusterinfra.rca.webconsole.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SameOriginMutationFilterTests {
    private final SameOriginMutationFilter filter = new SameOriginMutationFilter();

    @Test
    void rejectsCrossOriginMutationForCookieAuthentication() throws Exception {
        MockHttpServletRequest request = mutationRequest();
        request.addHeader(HttpHeaders.ORIGIN, "https://attacker.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void allowsSameOriginMutationForCookieAuthentication() throws Exception {
        MockHttpServletRequest request = mutationRequest();
        request.addHeader(HttpHeaders.ORIGIN, "https://rca.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void bearerAuthenticationDoesNotRequireBrowserOrigin() throws Exception {
        MockHttpServletRequest request = mutationRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer node-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    private MockHttpServletRequest mutationRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/clusters");
        request.setScheme("https");
        request.setServerName("rca.example.com");
        request.setServerPort(443);
        request.setCookies(new Cookie(PlatformAuthenticationFilter.SESSION_COOKIE, "session-token"));
        return request;
    }
}
