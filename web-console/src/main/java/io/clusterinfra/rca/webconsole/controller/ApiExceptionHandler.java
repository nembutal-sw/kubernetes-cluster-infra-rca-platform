package io.clusterinfra.rca.webconsole.controller;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> handleResponseStatus(
        ResponseStatusException exception,
        HttpServletRequest request
    ) {
        return ApiErrorResponse.response(
            request,
            exception.getStatusCode(),
            ApiErrorResponse.codeFor(exception.getStatusCode().value()),
            exception.getReason()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(
        MethodArgumentNotValidException exception,
        HttpServletRequest request
    ) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .orElse("request validation failed");
        return ApiErrorResponse.response(request, HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", detail);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Map<String, Object>> handleAuthentication(
        AuthenticationException exception,
        HttpServletRequest request
    ) {
        return ApiErrorResponse.response(
            request,
            HttpStatus.UNAUTHORIZED,
            "authentication_required",
            "login required"
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> handleAccessDenied(
        AccessDeniedException exception,
        HttpServletRequest request
    ) {
        return ApiErrorResponse.response(
            request,
            HttpStatus.FORBIDDEN,
            "access_denied",
            "insufficient role"
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> handleUnreadableMessage(
        HttpMessageNotReadableException exception,
        HttpServletRequest request
    ) {
        return ApiErrorResponse.response(
            request,
            HttpStatus.BAD_REQUEST,
            "malformed_json",
            "request body is not valid JSON"
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> handleUnsupportedMethod(
        HttpRequestMethodNotSupportedException exception,
        HttpServletRequest request
    ) {
        return ApiErrorResponse.response(
            request,
            HttpStatus.METHOD_NOT_ALLOWED,
            "method_not_allowed",
            "HTTP method is not supported for this endpoint"
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, Object>> handleUnsupportedMediaType(
        HttpMediaTypeNotSupportedException exception,
        HttpServletRequest request
    ) {
        return ApiErrorResponse.response(
            request,
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "unsupported_media_type",
            "request Content-Type is not supported"
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> handleMissingResource(
        NoResourceFoundException exception,
        HttpServletRequest request
    ) {
        return ApiErrorResponse.response(
            request,
            HttpStatus.NOT_FOUND,
            "resource_not_found",
            "API resource not found"
        );
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, Object>> handleDatabase(
        DataAccessException exception,
        HttpServletRequest request
    ) {
        return ApiErrorResponse.response(
            request,
            HttpStatus.SERVICE_UNAVAILABLE,
            "database_unavailable",
            "database operation failed"
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> handleIllegalArgument(
        IllegalArgumentException exception,
        HttpServletRequest request
    ) {
        return ApiErrorResponse.response(
            request,
            HttpStatus.UNPROCESSABLE_ENTITY,
            "invalid_argument",
            exception.getMessage()
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> handleUnexpected(
        Exception exception,
        HttpServletRequest request
    ) {
        String traceId = ApiErrorResponse.traceId(request);
        LOGGER.error("Unhandled API error trace_id={}", traceId, exception);
        return ApiErrorResponse.response(
            request,
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal_error",
            "unexpected platform error"
        );
    }
}
