package io.clusterinfra.rca.webconsole.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterinfra.rca.webconsole.controller.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

final class SecurityFilterSupport {
    private SecurityFilterSupport() {
    }

    static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath == null || contextPath.isEmpty() ? uri : uri.substring(contextPath.length());
    }

    static void writeError(
        ObjectMapper objectMapper,
        HttpServletRequest request,
        HttpServletResponse response,
        int status,
        String detail
    ) throws IOException {
        ApiErrorResponse.write(
            objectMapper,
            request,
            response,
            status,
            ApiErrorResponse.codeFor(status),
            detail
        );
    }
}
