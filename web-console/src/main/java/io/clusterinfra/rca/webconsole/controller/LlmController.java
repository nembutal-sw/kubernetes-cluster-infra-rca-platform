package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.LlmDiagnosticResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.LlmSetupGuideResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.LlmTestRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.LlmTestResponse;
import io.clusterinfra.rca.webconsole.domain.RcaModels.UserAccount;
import io.clusterinfra.rca.webconsole.security.AccessService;
import io.clusterinfra.rca.webconsole.service.AuditService;
import io.clusterinfra.rca.webconsole.service.LlmAnalysisService;
import io.clusterinfra.rca.webconsole.service.LlmConfigurationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
public class LlmController {
    private final LlmConfigurationService configuration;
    private final LlmAnalysisService llm;
    private final AuditService audit;
    private final AccessService access;

    public LlmController(
        LlmConfigurationService configuration,
        LlmAnalysisService llm,
        AuditService audit,
        AccessService access
    ) {
        this.configuration = configuration;
        this.llm = llm;
        this.audit = audit;
        this.access = access;
    }

    @GetMapping("/api/llm/diagnostics")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER','AUDITOR')")
    public LlmDiagnosticResponse diagnostics() {
        return configuration.diagnostics();
    }

    @GetMapping("/api/llm/setup")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER','AUDITOR')")
    public LlmSetupGuideResponse setupGuide() {
        return configuration.setupGuide();
    }

    @PostMapping("/api/llm/test")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public LlmTestResponse testConnection(
        @Valid @RequestBody LlmTestRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        if (!request.confirmed()) {
            throw new ResponseStatusException(BAD_REQUEST, "llm test confirmation is required");
        }
        LlmTestResponse response = llm.testConnection();
        UserAccount user = access.currentUser(authentication);
        audit.user(
            user,
            "llm.test",
            "llm",
            response.provider() == null || response.provider().isBlank() ? "none" : response.provider(),
            response.outcome(),
            auditDetails(response),
            servletRequest
        );
        return response;
    }

    private Map<String, Object> auditDetails(LlmTestResponse response) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("message", response.message());
        details.put("provider", response.provider());
        details.put("model", response.model());
        details.put("prompt_version", response.promptVersion());
        details.put("latency_ms", response.latencyMs() == null ? "" : response.latencyMs());
        details.put("response_chars", response.responseChars() == null ? "" : response.responseChars());
        details.put("error", response.error() == null ? "" : response.error());
        return details;
    }
}
