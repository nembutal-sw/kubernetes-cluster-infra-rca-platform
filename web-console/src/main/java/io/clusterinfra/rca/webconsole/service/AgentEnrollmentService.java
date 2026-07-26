package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentMode;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentProfile;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentProfileUpdateRequest;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository.AgentEnrollmentConfiguration;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.security.AgentSecurityPolicy;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Instant;
import java.time.Duration;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentEnrollmentService {
    private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9](?:[-a-z0-9]*[a-z0-9])?");
    private static final Pattern SERVICE_ACCOUNT = Pattern.compile(
        "[a-z0-9](?:[-a-z0-9.]*[a-z0-9])?"
    );
    private static final Pattern LABEL_NAME = Pattern.compile(
        "[A-Za-z0-9](?:[-_.A-Za-z0-9]*[A-Za-z0-9])?"
    );
    private static final Pattern LABEL_PREFIX = Pattern.compile(
        "(?:[a-z0-9](?:[-a-z0-9]*[a-z0-9])?\\.)*"
            + "[a-z0-9](?:[-a-z0-9]*[a-z0-9])?"
    );
    private static final Pattern IMAGE_DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final String DEFAULT_REVIEWER_TOKEN_PATH =
        "/var/run/secrets/kubernetes.io/serviceaccount/token";
    private static final String REVIEWER_TOKEN_ROOT =
        "/var/run/secrets/cluster-infra-rca-reviewers/";
    private static final String DEFAULT_DAEMONSET_NAME = "cluster-infra-rca-agent";

    private final AgentEnrollmentRepository enrollments;
    private final AgentRepository agents;
    private final ClusterRepository clusters;
    private final RcaConsoleProperties properties;
    private final AgentSecurityPolicy securityPolicy;

    public AgentEnrollmentService(
        AgentEnrollmentRepository enrollments,
        AgentRepository agents,
        ClusterRepository clusters,
        RcaConsoleProperties properties,
        AgentSecurityPolicy securityPolicy
    ) {
        this.enrollments = enrollments;
        this.agents = agents;
        this.clusters = clusters;
        this.properties = properties;
        this.securityPolicy = securityPolicy;
    }

    public AgentEnrollmentProfile profile(String clusterId) {
        requireCluster(clusterId);
        return withBootstrapTokenState(clusterId, enrollments.view(clusterId));
    }

    public AgentEnrollmentConfiguration configuration(String clusterId) {
        requireCluster(clusterId);
        return enrollments.findConfiguration(clusterId).orElse(null);
    }

    @Transactional
    public AgentEnrollmentProfile update(String clusterId, AgentEnrollmentProfileUpdateRequest request) {
        requireCluster(clusterId);
        enrollments.lockCluster(clusterId);
        if (request.mode() == AgentEnrollmentMode.bootstrap_token) {
            if (enrollments.findConfiguration(clusterId).isPresent()) {
                agents.revokeNodeTokensForEnrollmentChange(clusterId);
            }
            enrollments.delete(clusterId);
            return AgentEnrollmentProfile.bootstrap(clusterId, bootstrapTokenRequiresRotation(clusterId));
        }

        AgentEnrollmentConfiguration previous = enrollments.findConfiguration(clusterId).orElse(null);
        String apiServerUrl = apiServerUrl(request.apiServerUrl());
        String caBundlePem = request.caBundlePem() == null || request.caBundlePem().isBlank()
            ? previousCaBundle(previous)
            : request.caBundlePem().trim();
        if (caBundlePem.length() > 65535) {
            throw invalid("ca_bundle_pem exceeds the maximum length");
        }
        String audience = bounded(required(request.audience(), "audience"), 255, "audience");
        if (audience.chars().anyMatch(Character::isWhitespace)
            || audience.chars().anyMatch(Character::isISOControl)) {
            throw invalid("audience must not contain whitespace or control characters");
        }
        if (securityPolicy.isKubernetesApiAudience(audience)) {
            throw invalid(
                "audience must be dedicated to Agent enrollment and must not be a Kubernetes API audience"
            );
        }
        String namespace = kubernetesName(request.namespace(), 63, DNS_LABEL, "namespace");
        String serviceAccount = kubernetesName(
            request.serviceAccount(),
            253,
            SERVICE_ACCOUNT,
            "service_account"
        );
        String reviewerTokenPath = reviewerTokenPath(
            request.reviewerTokenPath(),
            previous == null || previous.reviewerTokenPath() == null
                || previous.reviewerTokenPath().isBlank()
                ? DEFAULT_REVIEWER_TOKEN_PATH
                : previous.reviewerTokenPath()
        );
        String expectedServiceAccountUid = optionalIdentity(
            request.expectedServiceAccountUid(),
            previous == null ? null : previous.expectedServiceAccountUid(),
            "expected_service_account_uid"
        );
        String expectedDaemonSetName = optionalKubernetesName(
            request.expectedDaemonSetName(),
            previous == null ? DEFAULT_DAEMONSET_NAME : previous.expectedDaemonSetName(),
            "expected_daemonset_name"
        );
        String expectedDaemonSetUid = optionalIdentity(
            request.expectedDaemonSetUid(),
            previous == null ? null : previous.expectedDaemonSetUid(),
            "expected_daemonset_uid"
        );
        Map<String, String> requiredPodLabels = requiredPodLabels(
            clusterId,
            request.requiredPodLabels(),
            previous == null ? null : previous.requiredPodLabels()
        );
        String allowedImageDigest = allowedImageDigest(
            request.allowedImageDigest(),
            previous == null ? null : previous.allowedImageDigest()
        );
        Instant now = Instant.now();
        AgentEnrollmentConfiguration candidate = new AgentEnrollmentConfiguration(
            clusterId,
            AgentEnrollmentMode.kubernetes_token_review,
            apiServerUrl,
            caBundlePem,
            certificateFingerprint(caBundlePem),
            audience,
            namespace,
            serviceAccount,
            previous == null ? 1 : previous.profileVersion(),
            reviewerTokenPath,
            expectedServiceAccountUid,
            expectedDaemonSetName,
            expectedDaemonSetUid,
            requiredPodLabels,
            allowedImageDigest,
            request.fallbackAllowedOrDefault(),
            previous == null ? now : previous.createdAt(),
            now
        );
        boolean changed = previous == null || !sameSecurityContract(previous, candidate);
        if (previous != null && changed) {
            candidate = withProfileVersion(candidate, previous.profileVersion() + 1);
        }
        AgentEnrollmentConfiguration saved = enrollments.save(candidate);
        if (changed) {
            agents.revokeNodeTokensForEnrollmentChange(clusterId);
        }
        if (!saved.bootstrapFallbackAllowed()) {
            clusters.revokeBootstrapToken(clusterId);
        }
        return withBootstrapTokenState(clusterId, saved.view());
    }

    private AgentEnrollmentProfile withBootstrapTokenState(
        String clusterId,
        AgentEnrollmentProfile profile
    ) {
        return new AgentEnrollmentProfile(
            profile.clusterId(),
            profile.mode(),
            profile.configured(),
            profile.apiServerUrl(),
            profile.caSha256(),
            profile.audience(),
            profile.namespace(),
            profile.serviceAccount(),
            profile.profileVersion(),
            profile.reviewerTokenPath(),
            profile.expectedServiceAccountUid(),
            profile.expectedDaemonSetName(),
            profile.expectedDaemonSetUid(),
            profile.requiredPodLabels(),
            profile.allowedImageDigest(),
            profile.workloadIdentityReady(),
            profile.bootstrapFallbackAllowed(),
            bootstrapTokenRequiresRotation(clusterId),
            profile.updatedAt()
        );
    }

    private boolean bootstrapTokenRequiresRotation(String clusterId) {
        return clusters.bootstrapTokenRequiresRotation(
            clusterId,
            Duration.ofSeconds(properties.getSecurity().getAgentBootstrapTokenTtlSeconds())
        );
    }

    private String previousCaBundle(AgentEnrollmentConfiguration previous) {
        if (previous == null || previous.caBundlePem() == null || previous.caBundlePem().isBlank()) {
            throw invalid("ca_bundle_pem is required for kubernetes_token_review");
        }
        return previous.caBundlePem();
    }

    private void requireCluster(String clusterId) {
        if (clusters.find(clusterId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "cluster not found");
        }
    }

    private String apiServerUrl(String value) {
        try {
            String raw = required(value, "api_server_url");
            URI uri = URI.create(raw);
            String path = uri.getPath();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || (path != null && !path.isBlank() && !"/".equals(path))) {
                throw invalid("api_server_url must be an HTTPS origin without credentials, path, query, or fragment");
            }
            int port = uri.getPort();
            if (port < -1 || port == 0 || port > 65535) {
                throw invalid("api_server_url contains an invalid port");
            }
            String host = uri.getHost().contains(":") ? "[" + uri.getHost() + "]" : uri.getHost();
            return "https://" + host + (port == -1 ? "" : ":" + port);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw invalid("api_server_url must be a valid HTTPS origin");
        }
    }

    private String certificateFingerprint(String pem) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certificates = factory.generateCertificates(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII))
            );
            if (certificates.isEmpty()) {
                throw invalid("ca_bundle_pem must contain at least one X.509 certificate");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Certificate certificate : certificates) {
                digest.update(certificate.getEncoded());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("ca_bundle_pem must contain valid X.509 certificates");
        }
    }

    private String kubernetesName(String value, int maximum, Pattern pattern, String field) {
        String normalized = bounded(required(value, field), maximum, field);
        if (!pattern.matcher(normalized).matches()) {
            throw invalid(field + " must be a valid Kubernetes name");
        }
        return normalized;
    }

    private String optionalKubernetesName(String value, String previous, String field) {
        String selected = value == null ? previous : value.trim();
        if (selected == null || selected.isBlank()) {
            return null;
        }
        return kubernetesName(selected, 253, SERVICE_ACCOUNT, field);
    }

    private String reviewerTokenPath(String value, String previous) {
        String selected = value == null ? previous : value.trim();
        if (selected == null || selected.isBlank()) {
            throw invalid("reviewer_token_path is required for kubernetes_token_review");
        }
        selected = bounded(selected, 4096, "reviewer_token_path");
        if (!selected.startsWith("/") || selected.chars().anyMatch(Character::isISOControl)) {
            throw invalid("reviewer_token_path must be an absolute path without control characters");
        }
        if (selected.contains("//")
            || java.util.Arrays.stream(selected.split("/"))
                .anyMatch(segment -> ".".equals(segment) || "..".equals(segment))
            || (!DEFAULT_REVIEWER_TOKEN_PATH.equals(selected)
                && !selected.startsWith(REVIEWER_TOKEN_ROOT))) {
            throw invalid(
                "reviewer_token_path must use the platform ServiceAccount token or the dedicated reviewer root"
            );
        }
        return selected;
    }

    private String optionalIdentity(String value, String previous, String field) {
        String selected = value == null ? previous : value.trim();
        if (selected == null || selected.isBlank()) {
            return null;
        }
        selected = bounded(selected, 255, field);
        if (selected.chars().anyMatch(Character::isWhitespace)
            || selected.chars().anyMatch(Character::isISOControl)) {
            throw invalid(field + " must not contain whitespace or control characters");
        }
        return selected;
    }

    private Map<String, String> requiredPodLabels(
        String clusterId,
        Map<String, String> requested,
        Map<String, String> previous
    ) {
        Map<String, String> selected;
        if (requested == null) {
            selected = previous == null || previous.isEmpty()
                ? Map.of(
                    "app.kubernetes.io/name", DEFAULT_DAEMONSET_NAME,
                    "cluster-infra-rca.io/cluster-id", clusterId
                )
                : previous;
        } else {
            selected = requested;
        }
        if (selected.isEmpty() || selected.size() > 32) {
            throw invalid("required_pod_labels must contain between 1 and 32 labels");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        selected.forEach((key, value) -> {
            String normalizedKey = key == null ? "" : key.trim();
            String normalizedValue = value == null ? "" : value.trim();
            validateLabelKey(normalizedKey);
            if (normalizedValue.length() > 63
                || (!normalizedValue.isEmpty() && !LABEL_NAME.matcher(normalizedValue).matches())) {
                throw invalid("required_pod_labels contains an invalid label value");
            }
            normalized.put(normalizedKey, normalizedValue);
        });
        if (!clusterId.equals(normalized.get("cluster-infra-rca.io/cluster-id"))) {
            throw invalid("required_pod_labels must bind cluster-infra-rca.io/cluster-id to the cluster");
        }
        return Map.copyOf(normalized);
    }

    private void validateLabelKey(String key) {
        int slash = key.indexOf('/');
        String prefix = slash < 0 ? null : key.substring(0, slash);
        String name = slash < 0 ? key : key.substring(slash + 1);
        if (name.isEmpty() || name.length() > 63 || !LABEL_NAME.matcher(name).matches()
            || (prefix != null && (prefix.length() > 253 || !LABEL_PREFIX.matcher(prefix).matches()))
            || (slash >= 0 && slash != key.lastIndexOf('/'))) {
            throw invalid("required_pod_labels contains an invalid label key");
        }
    }

    private String allowedImageDigest(String value, String previous) {
        String selected = value == null ? previous : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (selected == null || selected.isBlank()) {
            return null;
        }
        if (!IMAGE_DIGEST.matcher(selected).matches()) {
            throw invalid("allowed_image_digest must use sha256:<64 lowercase hex characters>");
        }
        return selected;
    }

    private boolean sameSecurityContract(
        AgentEnrollmentConfiguration previous,
        AgentEnrollmentConfiguration candidate
    ) {
        return Objects.equals(previous.apiServerUrl(), candidate.apiServerUrl())
            && Objects.equals(previous.caSha256(), candidate.caSha256())
            && Objects.equals(previous.audience(), candidate.audience())
            && Objects.equals(previous.namespace(), candidate.namespace())
            && Objects.equals(previous.serviceAccount(), candidate.serviceAccount())
            && Objects.equals(previous.reviewerTokenPath(), candidate.reviewerTokenPath())
            && Objects.equals(previous.expectedServiceAccountUid(), candidate.expectedServiceAccountUid())
            && Objects.equals(previous.expectedDaemonSetName(), candidate.expectedDaemonSetName())
            && Objects.equals(previous.expectedDaemonSetUid(), candidate.expectedDaemonSetUid())
            && Objects.equals(previous.requiredPodLabels(), candidate.requiredPodLabels())
            && Objects.equals(previous.allowedImageDigest(), candidate.allowedImageDigest())
            && previous.bootstrapFallbackAllowed() == candidate.bootstrapFallbackAllowed();
    }

    private AgentEnrollmentConfiguration withProfileVersion(
        AgentEnrollmentConfiguration value,
        long profileVersion
    ) {
        return new AgentEnrollmentConfiguration(
            value.clusterId(),
            value.mode(),
            value.apiServerUrl(),
            value.caBundlePem(),
            value.caSha256(),
            value.audience(),
            value.namespace(),
            value.serviceAccount(),
            profileVersion,
            value.reviewerTokenPath(),
            value.expectedServiceAccountUid(),
            value.expectedDaemonSetName(),
            value.expectedDaemonSetUid(),
            value.requiredPodLabels(),
            value.allowedImageDigest(),
            value.bootstrapFallbackAllowed(),
            value.createdAt(),
            value.updatedAt()
        );
    }

    private String bounded(String value, int maximum, String field) {
        if (value.length() > maximum) {
            throw invalid(field + " exceeds the maximum length");
        }
        return value;
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required for kubernetes_token_review");
        }
        return value.trim();
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
