package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import io.clusterinfra.rca.webconsole.domain.RcaModels.PlatformInfo;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlatformInfoController {
    private final RcaConsoleProperties properties;

    public PlatformInfoController(RcaConsoleProperties properties) {
        this.properties = properties;
    }

    @GetMapping({"/api/platform/info", "/api/v1/platform/info"})
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','APPROVER','AUDITOR')")
    public PlatformInfo info() {
        RcaConsoleProperties.Agent agent = properties.getAgent();
        return new PlatformInfo(
            agent.getPlatformVersion(),
            "v1",
            agent.getProtocolVersion(),
            agent.getMinimumSupportedProtocolVersion(),
            agent.getMinimumSupportedVersion()
        );
    }
}
