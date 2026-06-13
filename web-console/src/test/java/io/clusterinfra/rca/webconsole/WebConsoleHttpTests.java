package io.clusterinfra.rca.webconsole;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebConsoleHttpTests {
    private static final AtomicReference<HttpServer> API_SERVER = new AtomicReference<>();
    private static final String PUBLIC_API_BASE_URL = "https://rca.example.com";

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        HttpServer server = startApiServer();
        String apiBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        registry.add("rca.api-base-url", () -> apiBaseUrl);
        registry.add("rca.public-api-base-url", () -> PUBLIC_API_BASE_URL);
        registry.add("rca.proxy-timeout-seconds", () -> "5");
    }

    @AfterAll
    static void stopApiServer() {
        HttpServer server = API_SERVER.getAndSet(null);
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void consolePageRendersJspShellWithSecurityHeaders() {
        ResponseEntity<String> response = restTemplate.getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
            .contains("id=\"rca-console-root\"")
            .contains("data-api-base=\"/console-api\"")
            .contains("data-public-api-base=\"" + PUBLIC_API_BASE_URL + "\"")
            .contains("/assets/console-app.js");
        assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
            .contains("default-src 'self'")
            .contains("connect-src 'self'")
            .contains("frame-ancestors 'none'");
    }

    @Test
    void proxyForwardsAllowedAuthHeadersAndPreservesQueryString() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(MediaType.parseMediaTypes(MediaType.APPLICATION_JSON_VALUE));
        headers.setBearerAuth("agent-token");
        headers.set("X-Admin-Token", "admin-token");

        ResponseEntity<String> response = restTemplate.exchange(
            URI.create("/console-api/health?scope=node"),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody())
            .contains("\"status\":\"ok\"")
            .contains("\"method\":\"GET\"")
            .contains("\"path\":\"/health?scope=node\"")
            .contains("\"authorization\":true")
            .contains("\"adminToken\":true");
    }

    @Test
    void proxyForwardsJsonPostBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Token", "admin-token");

        ResponseEntity<String> response = restTemplate.exchange(
            URI.create("/console-api/echo"),
            HttpMethod.POST,
            new HttpEntity<>("{\"name\":\"smoke-cluster\"}", headers),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
            .contains("\"method\":\"POST\"")
            .contains("\"body\":\"{\\\"name\\\":\\\"smoke-cluster\\\"}\"")
            .contains("\"contentType\":\"application/json\"")
            .contains("\"adminToken\":true");
    }

    private static HttpServer startApiServer() {
        HttpServer existing = API_SERVER.get();
        if (existing != null) {
            return existing;
        }

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/health", exchange -> {
                String responseBody = "{"
                    + "\"status\":\"ok\","
                    + "\"method\":\"" + exchange.getRequestMethod() + "\","
                    + "\"path\":\"" + exchange.getRequestURI() + "\","
                    + "\"authorization\":" + exchange.getRequestHeaders().containsKey("Authorization") + ","
                    + "\"adminToken\":" + exchange.getRequestHeaders().containsKey("X-Admin-Token")
                    + "}";
                byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                exchange.sendResponseHeaders(HttpStatus.OK.value(), payload.length);
                exchange.getResponseBody().write(payload);
                exchange.close();
            });
            server.createContext("/echo", exchange -> {
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                String responseBody = "{"
                    + "\"method\":\"" + exchange.getRequestMethod() + "\","
                    + "\"body\":\"" + new String(requestBody, StandardCharsets.UTF_8).replace("\"", "\\\"") + "\","
                    + "\"contentType\":\"" + exchange.getRequestHeaders().getFirst("Content-Type") + "\","
                    + "\"adminToken\":" + exchange.getRequestHeaders().containsKey("X-Admin-Token")
                    + "}";
                byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                exchange.sendResponseHeaders(HttpStatus.OK.value(), payload.length);
                exchange.getResponseBody().write(payload);
                exchange.close();
            });
            server.start();
            API_SERVER.set(server);
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to start test API server", ex);
        }
    }
}
