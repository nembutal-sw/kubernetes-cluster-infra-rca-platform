package io.clusterinfra.rca.webconsole.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public final class ApiErrorResponse {
    public static final String TRACE_HEADER = "X-Request-ID";
    private static final String TRACE_ATTRIBUTE = ApiErrorResponse.class.getName() + ".traceId";

    private ApiErrorResponse() {
    }

    public static ResponseEntity<Map<String, Object>> response(
        HttpServletRequest request,
        HttpStatusCode status,
        String code,
        String detail
    ) {
        String traceId = traceId(request);
        return ResponseEntity.status(status)
            .header(TRACE_HEADER, traceId)
            .body(body(status.value(), code, detail, traceId));
    }

    public static void write(
        ObjectMapper objectMapper,
        HttpServletRequest request,
        HttpServletResponse response,
        int status,
        String code,
        String detail
    ) throws IOException {
        String traceId = traceId(request);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(TRACE_HEADER, traceId);
        objectMapper.writeValue(response.getWriter(), body(status, code, detail, traceId));
    }

    public static String codeFor(int status) {
        return switch (status) {
            case 400 -> "invalid_request";
            case 401 -> "authentication_required";
            case 403 -> "access_denied";
            case 404 -> "resource_not_found";
            case 405 -> "method_not_allowed";
            case 409 -> "request_conflict";
            case 413 -> "payload_too_large";
            case 415 -> "unsupported_media_type";
            case 422 -> "validation_failed";
            case 503 -> "service_unavailable";
            default -> status >= 500 ? "internal_error" : "request_failed";
        };
    }

    private static Map<String, Object> body(int status, String code, String detail, String traceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", clean(code, codeFor(status)));
        result.put("title", title(status));
        result.put("detail", clean(detail, "request failed"));
        result.put("suggestion", suggestion(status));
        result.put("trace_id", traceId);
        return result;
    }

    public static String traceId(HttpServletRequest request) {
        Object existing = request.getAttribute(TRACE_ATTRIBUTE);
        if (existing instanceof String value && !value.isBlank()) {
            return value;
        }
        String supplied = firstHeader(request, TRACE_HEADER, "X-Correlation-ID");
        String traceId = validTraceId(supplied) ? supplied.trim() : "req-" + UUID.randomUUID();
        request.setAttribute(TRACE_ATTRIBUTE, traceId);
        return traceId;
    }

    private static String firstHeader(HttpServletRequest request, String... names) {
        for (String name : names) {
            String value = request.getHeader(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static boolean validTraceId(String value) {
        return value != null
            && value.length() <= 128
            && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*");
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String title(int status) {
        return switch (status) {
            case 400 -> "Invalid request";
            case 401 -> "Authentication required";
            case 403 -> "Access denied";
            case 404 -> "Resource not found";
            case 405 -> "Method not allowed";
            case 409 -> "Request conflict";
            case 413 -> "Payload too large";
            case 415 -> "Unsupported media type";
            case 422 -> "Validation failed";
            case 503 -> "Service unavailable";
            default -> status >= 500 ? "Internal server error" : "Request failed";
        };
    }

    private static String suggestion(int status) {
        return switch (status) {
            case 401 -> "Sign in again and retry the request.";
            case 403 -> "Verify that your account has the required role.";
            case 404 -> "Verify the resource identifier and try again.";
            case 405 -> "Use a supported HTTP method for this endpoint.";
            case 409 -> "Refresh the resource state before retrying.";
            case 413 -> "Reduce the request payload size and retry.";
            case 415 -> "Send the request with a supported Content-Type.";
            case 422, 400 -> "Correct the request and retry.";
            case 503 -> "Retry after the platform dependency recovers.";
            default -> status >= 500
                ? "Retry the request and provide the trace ID if the failure continues."
                : "Review the request and try again.";
        };
    }
}
