package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${rca.proxy-path:/console-api}")
public class ApiProxyController {
    private static final Set<String> FORWARDED_REQUEST_HEADERS = Set.of(
        "accept",
        "authorization",
        "content-type",
        "x-admin-token"
    );
    private static final Set<String> FORWARDED_RESPONSE_HEADERS = Set.of(
        "content-type",
        "cache-control",
        "etag"
    );

    private final RcaConsoleProperties properties;
    private final HttpClient httpClient;

    public ApiProxyController(RcaConsoleProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(properties.getProxyTimeoutSeconds()))
            .build();
    }

    @RequestMapping({"", "/**"})
    public ResponseEntity<byte[]> proxy(
        HttpServletRequest servletRequest,
        @RequestBody(required = false) byte[] body
    ) throws IOException, InterruptedException {
        URI targetUri = buildTargetUri(servletRequest);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(targetUri)
            .timeout(Duration.ofSeconds(properties.getProxyTimeoutSeconds()));

        copyRequestHeaders(servletRequest, requestBuilder);
        requestBuilder.method(
            servletRequest.getMethod(),
            body == null || body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)
        );

        HttpResponse<byte[]> upstreamResponse = httpClient.send(
            requestBuilder.build(),
            HttpResponse.BodyHandlers.ofByteArray()
        );
        HttpHeaders responseHeaders = new HttpHeaders();
        upstreamResponse.headers().map().forEach((name, values) -> {
            if (FORWARDED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> responseHeaders.add(name, value));
            }
        });
        responseHeaders.setCacheControl("no-store");
        return new ResponseEntity<>(
            upstreamResponse.body(),
            responseHeaders,
            HttpStatusCode.valueOf(upstreamResponse.statusCode())
        );
    }

    private URI buildTargetUri(HttpServletRequest servletRequest) {
        String contextPath = servletRequest.getContextPath() == null ? "" : servletRequest.getContextPath();
        String proxyPath = normalizeProxyPath(properties.getProxyPath());
        String requestUri = servletRequest.getRequestURI();
        String prefix = contextPath + proxyPath;
        String apiPath = requestUri.startsWith(prefix) ? requestUri.substring(prefix.length()) : "";
        if (apiPath.isBlank()) {
            apiPath = "/";
        }

        String baseUrl = properties.getApiBaseUrl().toString();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String query = servletRequest.getQueryString();
        return URI.create(baseUrl + apiPath + (query == null || query.isBlank() ? "" : "?" + query));
    }

    private String normalizeProxyPath(String proxyPath) {
        if (proxyPath == null || proxyPath.isBlank()) {
            return "/console-api";
        }
        return proxyPath.startsWith("/") ? proxyPath : "/" + proxyPath;
    }

    private void copyRequestHeaders(HttpServletRequest servletRequest, HttpRequest.Builder requestBuilder) {
        servletRequest.getHeaderNames().asIterator().forEachRemaining(name -> {
            if (!FORWARDED_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            servletRequest.getHeaders(name).asIterator().forEachRemaining(value -> requestBuilder.header(name, value));
        });
    }
}
