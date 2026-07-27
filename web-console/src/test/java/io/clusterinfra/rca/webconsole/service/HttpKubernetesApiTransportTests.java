package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentMode;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository.AgentEnrollmentConfiguration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class HttpKubernetesApiTransportTests {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsOnlyUpToTheConfiguredResponseBoundary() throws Exception {
        byte[] body = new byte[] {1, 2, 3, 4};

        assertThat(HttpKubernetesApiTransport.readBounded(
            new ByteArrayInputStream(body),
            body.length,
            "TokenReview"
        )).containsExactly(body);
    }

    @Test
    void rejectsAResponseBeforeBufferingBeyondTheBoundary() {
        ByteArrayInputStream body = new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5, 6});

        assertThatThrownBy(() -> HttpKubernetesApiTransport.readBounded(body, 4, "TokenReview"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("response exceeded the size limit");
        assertThat(body.available()).isEqualTo(1);
    }

    @Test
    void rejectedCurrentCredentialFallsBackToPreviousCredential() throws Exception {
        Path current = token("current", "current-token");
        Path previous = token("previous", "previous-token");
        ReviewerCredentialLifecycleService lifecycle = mock(
            ReviewerCredentialLifecycleService.class
        );
        AgentEnrollmentConfiguration configuration = configuration();
        when(lifecycle.activeTokenPaths(configuration))
            .thenReturn(List.of(current.toString(), previous.toString()));
        ObjectMapper objectMapper = new ObjectMapper();
        HttpKubernetesApiTransport transport = new StubTransport(
            objectMapper,
            new ReviewerCredentialInspector(objectMapper),
            lifecycle,
            request -> {
                String authorization = request.headers()
                    .firstValue("Authorization")
                    .orElse("");
                if ("Bearer current-token".equals(authorization)) {
                    throw new HttpKubernetesApiTransport.ReviewerCredentialRejectedException();
                }
                return objectMapper.createObjectNode().put("credential", authorization);
            }
        );

        JsonNode response = transport.pod(configuration, "default", "pod-a");

        assertThat(response.path("credential").asText()).isEqualTo("Bearer previous-token");
    }

    @Test
    void unreadableCurrentCredentialFallsBackButUnrelatedFailureDoesNot() throws Exception {
        Path previous = token("previous", "previous-token");
        ReviewerCredentialLifecycleService lifecycle = mock(
            ReviewerCredentialLifecycleService.class
        );
        AgentEnrollmentConfiguration configuration = configuration();
        when(lifecycle.activeTokenPaths(configuration)).thenReturn(List.of(
            temporaryDirectory.resolve("missing").toString(),
            previous.toString()
        ));
        ObjectMapper objectMapper = new ObjectMapper();
        HttpKubernetesApiTransport fallbackTransport = new StubTransport(
            objectMapper,
            new ReviewerCredentialInspector(objectMapper),
            lifecycle,
            request -> objectMapper.createObjectNode().put("used", "previous")
        );

        assertThat(fallbackTransport.pod(configuration, "default", "pod-a").path("used").asText())
            .isEqualTo("previous");

        when(lifecycle.activeTokenPaths(configuration)).thenReturn(List.of(previous.toString()));
        HttpKubernetesApiTransport failingTransport = new StubTransport(
            objectMapper,
            new ReviewerCredentialInspector(objectMapper),
            lifecycle,
            request -> {
                throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Kubernetes API unavailable"
                );
            }
        );
        assertThatThrownBy(() -> failingTransport.pod(configuration, "default", "pod-a"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Kubernetes API unavailable");
    }

    private Path token(String name, String value) throws IOException {
        Path path = temporaryDirectory.resolve(name);
        Files.writeString(path, value, StandardCharsets.UTF_8);
        return path;
    }

    private AgentEnrollmentConfiguration configuration() {
        Instant now = Instant.now();
        return new AgentEnrollmentConfiguration(
            "cluster-1",
            AgentEnrollmentMode.kubernetes_token_review,
            "https://kubernetes.example:6443",
            "test-ca",
            "test-sha",
            "cluster-infra-rca-agent-enrollment",
            "rca-system",
            "cluster-infra-rca-agent",
            1,
            "/current",
            2,
            "/previous",
            now.plusSeconds(600),
            now,
            "service-account-uid",
            "cluster-infra-rca-agent",
            "daemonset-uid",
            Map.of("cluster-infra-rca.io/cluster-id", "cluster-1"),
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            null,
            false,
            now,
            now
        );
    }

    @FunctionalInterface
    private interface RequestHandler {
        JsonNode send(HttpRequest request);
    }

    private static final class StubTransport extends HttpKubernetesApiTransport {
        private final RequestHandler handler;

        private StubTransport(
            ObjectMapper objectMapper,
            ReviewerCredentialInspector inspector,
            ReviewerCredentialLifecycleService lifecycle,
            RequestHandler handler
        ) {
            super(objectMapper, inspector, lifecycle);
            this.handler = handler;
        }

        @Override
        JsonNode send(
            AgentEnrollmentConfiguration configuration,
            HttpRequest request,
            String operation
        ) {
            return handler.send(request);
        }
    }
}
