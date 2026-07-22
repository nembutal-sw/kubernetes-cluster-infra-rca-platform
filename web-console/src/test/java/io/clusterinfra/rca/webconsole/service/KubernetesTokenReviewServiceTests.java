package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentMode;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository.AgentEnrollmentConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class KubernetesTokenReviewServiceTests {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void acceptsAudienceServiceAccountAndCurrentPodNodeBinding() throws Exception {
        FakeTransport transport = new FakeTransport(review("rca-agent", "pod-uid-1"), pod("worker-1", false));
        KubernetesTokenReviewService service = new KubernetesTokenReviewService(transport);

        var identity = service.verify(configuration(), "projected-token", "worker-1");

        assertThat(identity.method()).isEqualTo("kubernetes_token_review");
        assertThat(identity.subject()).isEqualTo("system:serviceaccount:rca-system:cluster-infra-rca-agent");
        assertThat(identity.podName()).isEqualTo("rca-agent");
        assertThat(identity.podUid()).isEqualTo("pod-uid-1");
        assertThat(transport.podLookups).isEqualTo(1);
    }

    @Test
    void rejectsTokenReviewWithoutExpectedAudience() throws Exception {
        JsonNode review = review("rca-agent", "pod-uid-1");
        ((com.fasterxml.jackson.databind.node.ObjectNode) review.path("status"))
            .set("audiences", JSON.createArrayNode().add("different-audience"));
        KubernetesTokenReviewService service = new KubernetesTokenReviewService(
            new FakeTransport(review, pod("worker-1", false))
        );

        assertUnauthorized(() -> service.verify(configuration(), "projected-token", "worker-1"), "audience");
    }

    @Test
    void rejectsServiceAccountMismatchBeforePodLookup() throws Exception {
        JsonNode review = review("rca-agent", "pod-uid-1");
        ((com.fasterxml.jackson.databind.node.ObjectNode) review.path("status").path("user"))
            .put("username", "system:serviceaccount:rca-system:other-agent");
        FakeTransport transport = new FakeTransport(review, pod("worker-1", false));
        KubernetesTokenReviewService service = new KubernetesTokenReviewService(transport);

        assertUnauthorized(() -> service.verify(configuration(), "projected-token", "worker-1"), "ServiceAccount");
        assertThat(transport.podLookups).isZero();
    }

    @Test
    void rejectsUnverifiedNodeMetadataWhenCurrentPodBindingDiffers() throws Exception {
        KubernetesTokenReviewService service = new KubernetesTokenReviewService(
            new FakeTransport(review("rca-agent", "pod-uid-1"), pod("worker-2", false))
        );

        assertUnauthorized(() -> service.verify(configuration(), "projected-token", "worker-1"), "Pod binding");
    }

    @Test
    void rejectsTerminatingPodEvenWhenTokenReviewSucceeds() throws Exception {
        KubernetesTokenReviewService service = new KubernetesTokenReviewService(
            new FakeTransport(review("rca-agent", "pod-uid-1"), pod("worker-1", true))
        );

        assertUnauthorized(() -> service.verify(configuration(), "projected-token", "worker-1"), "Pod binding");
    }

    private static AgentEnrollmentConfiguration configuration() {
        Instant now = Instant.parse("2026-07-22T00:00:00Z");
        return new AgentEnrollmentConfiguration(
            "cluster-1",
            AgentEnrollmentMode.kubernetes_token_review,
            "https://kubernetes.example:6443",
            "test-ca",
            "ca-sha",
            "https://kubernetes.default.svc",
            "rca-system",
            "cluster-infra-rca-agent",
            false,
            now,
            now
        );
    }

    private static JsonNode review(String podName, String podUid) throws Exception {
        return JSON.readTree("""
            {
              "status": {
                "authenticated": true,
                "audiences": ["https://kubernetes.default.svc"],
                "user": {
                  "username": "system:serviceaccount:rca-system:cluster-infra-rca-agent",
                  "uid": "service-account-uid",
                  "groups": [
                    "system:serviceaccounts",
                    "system:serviceaccounts:rca-system",
                    "system:authenticated"
                  ],
                  "extra": {
                    "authentication.kubernetes.io/pod-name": ["%s"],
                    "authentication.kubernetes.io/pod-uid": ["%s"]
                  }
                }
              }
            }
            """.formatted(podName, podUid));
    }

    private static JsonNode pod(String nodeName, boolean terminating) throws Exception {
        return JSON.readTree("""
            {
              "metadata": {
                "name": "rca-agent",
                "namespace": "rca-system",
                "uid": "pod-uid-1"%s
              },
              "spec": {
                "serviceAccountName": "cluster-infra-rca-agent",
                "nodeName": "%s"
              }
            }
            """.formatted(
                terminating ? ",\n\"deletionTimestamp\": \"2026-07-22T00:00:00Z\"" : "",
                nodeName
            ));
    }

    private static void assertUnauthorized(ThrowingCall call, String message) {
        assertThatThrownBy(call::run)
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("401")
            .hasMessageContaining(message);
    }

    private interface ThrowingCall {
        void run();
    }

    private static final class FakeTransport implements KubernetesApiTransport {
        private final JsonNode review;
        private final JsonNode pod;
        private int podLookups;

        private FakeTransport(JsonNode review, JsonNode pod) {
            this.review = review;
            this.pod = pod;
        }

        @Override
        public JsonNode reviewToken(AgentEnrollmentConfiguration configuration, String token) {
            return review;
        }

        @Override
        public JsonNode pod(
            AgentEnrollmentConfiguration configuration,
            String token,
            String namespace,
            String podName
        ) {
            podLookups++;
            return pod;
        }
    }
}
