package io.clusterinfra.rca.webconsole.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class ReviewerCredentialPaths {
    static final String PLATFORM_SERVICE_ACCOUNT_TOKEN =
        "/var/run/secrets/kubernetes.io/serviceaccount/token";
    static final String EXTERNAL_REVIEWER_ROOT =
        "/var/run/secrets/cluster-infra-rca-reviewers/";

    private ReviewerCredentialPaths() {
    }

    static String validate(String value, String field) {
        String selected = value == null ? "" : value.trim();
        if (selected.isBlank()) {
            throw invalid(field + " is required");
        }
        if (selected.length() > 4096
            || !selected.startsWith("/")
            || selected.chars().anyMatch(Character::isISOControl)
            || selected.contains("//")
            || java.util.Arrays.stream(selected.split("/"))
                .anyMatch(segment -> ".".equals(segment) || "..".equals(segment))
            || (!PLATFORM_SERVICE_ACCOUNT_TOKEN.equals(selected)
                && !selected.startsWith(EXTERNAL_REVIEWER_ROOT))) {
            throw invalid(
                field + " must use the platform ServiceAccount token or the dedicated reviewer root"
            );
        }
        return selected;
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
