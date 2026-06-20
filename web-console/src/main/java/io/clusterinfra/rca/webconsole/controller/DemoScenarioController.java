package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.DemoScenarioRunRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.DemoScenarioRunResponse;
import io.clusterinfra.rca.webconsole.security.AccessService;
import io.clusterinfra.rca.webconsole.service.DemoScenarioService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoScenarioController {
    private final DemoScenarioService demos;
    private final AccessService access;

    public DemoScenarioController(DemoScenarioService demos, AccessService access) {
        this.demos = demos;
        this.access = access;
    }

    @GetMapping("/api/demo/scenarios")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public Map<String, Object> scenarios() {
        return Map.of("enabled", demos.enabled(), "scenarios", demos.scenarios());
    }

    @PostMapping("/api/demo/scenarios/{scenarioKey}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public DemoScenarioRunResponse run(
        @PathVariable String scenarioKey,
        @Valid @RequestBody DemoScenarioRunRequest request,
        Authentication authentication
    ) {
        return demos.run(scenarioKey, request, access.currentUser(authentication));
    }
}
