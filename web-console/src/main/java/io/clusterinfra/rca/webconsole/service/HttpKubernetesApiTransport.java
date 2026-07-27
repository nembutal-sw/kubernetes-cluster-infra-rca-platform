package io.clusterinfra.rca.webconsole.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository.AgentEnrollmentConfiguration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class HttpKubernetesApiTransport implements KubernetesApiTransport {
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;
    private final ReviewerCredentialInspector credentialInspector;
    private final ReviewerCredentialLifecycleService credentialLifecycle;
    private final Map<String, HttpClient> clients = Collections.synchronizedMap(
        new LinkedHashMap<>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, HttpClient> eldest) {
                return size() > 256;
            }
        }
    );

    public HttpKubernetesApiTransport(
        ObjectMapper objectMapper,
        ReviewerCredentialInspector credentialInspector,
        ReviewerCredentialLifecycleService credentialLifecycle
    ) {
        this.objectMapper = objectMapper;
        this.credentialInspector = credentialInspector;
        this.credentialLifecycle = credentialLifecycle;
    }

    @Override
    public JsonNode reviewToken(AgentEnrollmentConfiguration configuration, String token) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                "apiVersion", "authentication.k8s.io/v1",
                "kind", "TokenReview",
                "spec", Map.of("token", token, "audiences", java.util.List.of(configuration.audience()))
            ));
            return sendWithReviewerCredential(
                configuration,
                "TokenReview",
                reviewerToken -> requestBuilder(
                    configuration,
                    "/apis/authentication.k8s.io/v1/tokenreviews",
                    reviewerToken
                )
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build()
            );
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable("Kubernetes TokenReview request could not be created", exception);
        }
    }

    @Override
    public JsonNode pod(
        AgentEnrollmentConfiguration configuration,
        String namespace,
        String podName
    ) {
        String path = "/api/v1/namespaces/" + segment(namespace) + "/pods/" + segment(podName);
        return sendWithReviewerCredential(
            configuration,
            "Pod lookup",
            reviewerToken -> requestBuilder(configuration, path, reviewerToken).GET().build()
        );
    }

    JsonNode sendWithReviewerCredential(
        AgentEnrollmentConfiguration configuration,
        String operation,
        Function<String, HttpRequest> requestFactory
    ) {
        Exception lastReadFailure = null;
        boolean rejected = false;
        for (String tokenPath : credentialLifecycle.activeTokenPaths(configuration)) {
            try {
                String reviewerToken = credentialInspector.readToken(tokenPath);
                return send(configuration, requestFactory.apply(reviewerToken), operation);
            } catch (ReviewerCredentialRejectedException exception) {
                rejected = true;
            } catch (IOException exception) {
                lastReadFailure = exception;
            }
        }
        if (rejected) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                operation + " reviewer credential was rejected"
            );
        }
        throw unavailable("Kubernetes reviewer token file could not be read", lastReadFailure);
    }

    private HttpRequest.Builder requestBuilder(
        AgentEnrollmentConfiguration configuration,
        String path,
        String token
    ) {
        return HttpRequest.newBuilder(URI.create(configuration.apiServerUrl() + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + token);
    }

    JsonNode send(
        AgentEnrollmentConfiguration configuration,
        HttpRequest request,
        String operation
    ) {
        try {
            HttpResponse<InputStream> response = client(configuration).send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
            );
            try (InputStream bodyStream = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    if (response.statusCode() == 401 || response.statusCode() == 403) {
                        throw new ReviewerCredentialRejectedException();
                    }
                    if (response.statusCode() == 404) {
                        throw new ResponseStatusException(
                            HttpStatus.UNAUTHORIZED,
                            operation + " was rejected"
                        );
                    }
                    throw unavailable(operation + " returned HTTP " + response.statusCode(), null);
                }
                byte[] body = readBounded(bodyStream, MAX_RESPONSE_BYTES, operation);
                JsonNode parsed = objectMapper.readTree(body);
                if (parsed == null || !parsed.isObject()) {
                    throw unavailable(operation + " returned an invalid JSON object", null);
                }
                return parsed;
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable(operation + " was interrupted", exception);
        } catch (Exception exception) {
            throw unavailable(operation + " failed", exception);
        }
    }

    static byte[] readBounded(InputStream input, int maximumBytes, String operation) throws IOException {
        byte[] body = input.readNBytes(maximumBytes + 1);
        if (body.length > maximumBytes) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                operation + " response exceeded the size limit"
            );
        }
        return body;
    }

    private HttpClient client(AgentEnrollmentConfiguration configuration) {
        String key = configuration.apiServerUrl() + "#" + configuration.caSha256();
        synchronized (clients) {
            return clients.computeIfAbsent(key, ignored -> buildClient(configuration.caBundlePem()));
        }
    }

    private HttpClient buildClient(String caBundlePem) {
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certificates = certificateFactory.generateCertificates(
                new ByteArrayInputStream(caBundlePem.getBytes(StandardCharsets.US_ASCII))
            );
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            int index = 0;
            for (Certificate certificate : certificates) {
                trustStore.setCertificateEntry("cluster-ca-" + index++, certificate);
            }
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            );
            trustManagers.init(trustStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers.getTrustManagers(), null);
            return HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        } catch (Exception exception) {
            throw unavailable("Kubernetes API trust configuration is invalid", exception);
        }
    }

    private String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private ResponseStatusException unavailable(String message, Throwable cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }

    static final class ReviewerCredentialRejectedException extends RuntimeException {
    }
}
