package io.clusterinfra.rca.webconsole.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ConsolePageController {
    @GetMapping({
        "/console",
        "/overview",
        "/clusters",
        "/clusters/{clusterId}",
        "/reports",
        "/reports/{reportId}",
        "/incidents",
        "/incidents/{incidentId}",
        "/pipeline",
        "/audit",
        "/webhooks",
        "/settings"
    })
    public String console() {
        return "forward:/index.html";
    }
}
