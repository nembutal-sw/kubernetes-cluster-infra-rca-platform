package io.clusterinfra.rca.webconsole.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;

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
        HttpServletResponse response,
        int status,
        String detail
    ) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of("detail", detail));
    }
}
