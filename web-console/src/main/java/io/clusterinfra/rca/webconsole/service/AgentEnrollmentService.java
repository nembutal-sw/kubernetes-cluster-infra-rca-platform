package io.clusterinfra.rca.webconsole.service;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentMode;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentProfile;
import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentEnrollmentProfileUpdateRequest;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository.AgentEnrollmentConfiguration;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
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

    private final AgentEnrollmentRepository enrollments;
    private final ClusterRepository clusters;
    private final RcaConsoleProperties properties;

    public AgentEnrollmentService(
        AgentEnrollmentRepository enrollments,
        ClusterRepository clusters,
        RcaConsoleProperties properties
    ) {
        this.enrollments = enrollments;
        this.clusters = clusters;
        this.properties = properties;
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
        if (request.mode() == AgentEnrollmentMode.bootstrap_token) {
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
        String namespace = kubernetesName(request.namespace(), 63, DNS_LABEL, "namespace");
        String serviceAccount = kubernetesName(
            request.serviceAccount(),
            253,
            SERVICE_ACCOUNT,
            "service_account"
        );
        Instant now = Instant.now();
        AgentEnrollmentConfiguration saved = enrollments.save(new AgentEnrollmentConfiguration(
            clusterId,
            AgentEnrollmentMode.kubernetes_token_review,
            apiServerUrl,
            caBundlePem,
            certificateFingerprint(caBundlePem),
            audience,
            namespace,
            serviceAccount,
            request.fallbackAllowedOrDefault(),
            previous == null ? now : previous.createdAt(),
            now
        ));
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
