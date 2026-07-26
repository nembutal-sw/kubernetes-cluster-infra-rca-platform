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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final Map<String, HttpClient> clients = Collections.synchronizedMap(
        new LinkedHashMap<>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, HttpClient> eldest) {
                return size() > 256;
            }
        }
    );

    public HttpKubernetesApiTransport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public JsonNode reviewToken(AgentEnrollmentConfiguration configuration, String token) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                "apiVersion", "authentication.k8s.io/v1",
                "kind", "TokenReview",
                "spec", Map.of("token", token, "audiences", java.util.List.of(configuration.audience()))
            ));
            HttpRequest request = requestBuilder(
                configuration,
                "/apis/authentication.k8s.io/v1/tokenreviews",
                reviewerToken(configuration)
            )
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
            return send(configuration, request, "TokenReview");
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
        HttpRequest request = requestBuilder(configuration, path, reviewerToken(configuration)).GET().build();
        return send(configuration, request, "Pod lookup");
    }

    private String reviewerToken(AgentEnrollmentConfiguration configuration) {
        try (InputStream input = Files.newInputStream(Path.of(configuration.reviewerTokenPath()))) {
            String token = new String(
                readBounded(input, 32768, "Kubernetes reviewer token"),
                StandardCharsets.UTF_8
            ).trim();
            if (token.isEmpty() || token.chars().anyMatch(Character::isWhitespace)) {
                throw unavailable("Kubernetes reviewer token file is empty or invalid", null);
            }
            return token;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable("Kubernetes reviewer token file could not be read", exception);
        }
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

    private JsonNode send(
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
                    if (response.statusCode() == 401
                        || response.statusCode() == 403
                        || response.statusCode() == 404) {
                        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, operation + " was rejected");
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
}
