package io.clusterinfra.rca.webconsole.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentMode;
import io.clusterinfra.rca.webconsole.domain.RcaModels.Cluster;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterStatus;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository.AgentEnrollmentConfiguration;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
class AgentManifestServiceTests {
    @Mock
    private ManifestTokenService manifestTokens;

    @Mock
    private Environment environment;

    @Mock
    private AgentEnrollmentService enrollments;

    private AgentManifestService manifests;

    @BeforeEach
    void setUp() {
        manifests = new AgentManifestService(
            new RcaConsoleProperties(),
            manifestTokens,
            environment,
            enrollments
        );
    }

    @Test
    void tokenReviewManifestUsesProjectedIdentityWithoutBootstrapSecret() throws Exception {
        Cluster cluster = cluster(null);
        when(enrollments.configuration(cluster.clusterId())).thenReturn(configuration());

        String rendered = new ObjectMapper().writeValueAsString(manifests.manifest(cluster, options("rca-system")));

        assertThat(rendered)
            .contains("kubernetes-token-review")
            .contains("AGENT_IDENTITY_TOKEN_PATH")
            .contains("serviceAccountToken")
            .contains("cluster-infra-rca-agent-enrollment")
            .contains("daemonsets")
            .contains("\"cluster-infra-rca.io/cluster-id\":\"cluster-1\"")
            .contains("custom-rca-agent")
            .doesNotContain("tokenreviews")
            .doesNotContain("agent-token")
            .doesNotContain("AGENT_TOKEN");
    }

    @Test
    void tokenReviewManifestRejectsNamespaceDifferentFromTrustedProfile() {
        Cluster cluster = cluster(null);
        when(enrollments.configuration(cluster.clusterId())).thenReturn(configuration());

        assertThatThrownBy(() -> manifests.manifest(cluster, options("other-system")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must match");
    }

    @Test
    void backendUrlRemovesTrailingSlashesWithoutChangingTheAuthority() {
        assertThat(manifests.validateBackendUrl("https://rca.example.com:8443/api///"))
            .isEqualTo("https://rca.example.com:8443/api");
    }

    @Test
    void backendUrlRejectsAnEmptyOrAuthorityOnlyValueAfterNormalization() {
        assertThatThrownBy(() -> manifests.validateBackendUrl("////"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("absolute http or https URL");
    }

    @Test
    void bootstrapManifestRetainsExistingSecretContract() throws Exception {
        Cluster cluster = cluster("bootstrap-secret");
        when(enrollments.configuration(cluster.clusterId())).thenReturn(null);

        String rendered = new ObjectMapper().writeValueAsString(manifests.manifest(cluster, options("rca-system")));

        assertThat(rendered)
            .contains("bootstrap-token")
            .contains("agent-token")
            .contains("bootstrap-secret")
            .contains("AGENT_TOKEN")
            .doesNotContain("serviceAccountToken");
    }

    @Test
    void tokenReviewInstallCommandDoesNotExposeBootstrapCredential() {
        Cluster cluster = cluster(null);
        when(enrollments.configuration(cluster.clusterId())).thenReturn(configuration());
        when(manifestTokens.issue(cluster.clusterId(), "admin"))
            .thenReturn(new ManifestTokenService.IssuedManifestToken(
                "one-time-manifest-token",
                Instant.parse("2026-07-22T01:00:00Z")
            ));

        var command = manifests.installCommand(
            cluster,
            "https://rca.example.com",
            "registry.example/rca-agent:1.0.0",
            null,
            "admin"
        );

        assertThat(command.namespace()).isEqualTo("rca-system");
        assertThat(String.join("\n", command.commands()))
            .contains("--from-literal=cluster-id=cluster-1")
            .contains("manifest_token=one-time-manifest-token")
            .doesNotContain("agent-token")
            .doesNotContain("ROTATE_AGENT_TOKEN");
    }

    @Test
    void generatedManifestMatchesTheSharedIdentityAndRbacContract() throws Exception {
        Cluster cluster = cluster(null);
        when(enrollments.configuration(cluster.clusterId())).thenReturn(configuration());
        ObjectMapper json = new ObjectMapper();
        Path contractPath = Path.of("..", "config", "agent-manifest-contract.json");
        if (!Files.exists(contractPath)) {
            contractPath = Path.of("config", "agent-manifest-contract.json");
        }
        JsonNode contract = json.readTree(Files.readString(contractPath));
        String rendered = json.writeValueAsString(manifests.manifest(cluster, options("rca-system")));

        contract.path("identityLabels").forEach(label ->
            assertThat(rendered).contains("\"" + label.asText() + "\"")
        );
        contract.path("agentRbac").path("requiredResources").forEach(resource ->
            assertThat(rendered).contains("\"" + resource.asText() + "\"")
        );
        contract.path("agentRbac").path("forbiddenResources").forEach(resource ->
            assertThat(rendered).doesNotContain("\"" + resource.asText() + "\"")
        );
        assertThat(rendered)
            .contains("\"drop\":[\"ALL\"]")
            .contains("\"name\":\"" + contract.path("containerName").asText() + "\"")
            .contains("\"name\":\"" + contract.path("daemonSetName").asText() + "\"");
    }

    private Cluster cluster(String bootstrapToken) {
        return new Cluster(
            "cluster-1",
            "production",
            "prod",
            null,
            ClusterStatus.agent_pending,
            bootstrapToken,
            Instant.now(),
            null
        );
    }

    private AgentEnrollmentConfiguration configuration() {
        Instant now = Instant.now();
        return new AgentEnrollmentConfiguration(
            "cluster-1",
            AgentEnrollmentMode.kubernetes_token_review,
            "https://kubernetes.example:6443",
            "test-ca",
            "test-ca-sha",
            "cluster-infra-rca-agent-enrollment",
            "rca-system",
            "custom-rca-agent",
            false,
            now,
            now
        );
    }

    private AgentManifestService.ManifestOptions options(String namespace) {
        return new AgentManifestService.ManifestOptions(
            "https://rca.example.com",
            "registry.example/rca-agent:1.0.0",
            namespace,
            15,
            10,
            5,
            5,
            "6443,9345",
            "",
            "file",
            "safe"
        );
    }
}
