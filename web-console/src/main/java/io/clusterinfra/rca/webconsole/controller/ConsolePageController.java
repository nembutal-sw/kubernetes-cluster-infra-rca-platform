package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.config.RcaConsoleProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ConsolePageController {
    private final RcaConsoleProperties properties;

    public ConsolePageController(RcaConsoleProperties properties) {
        this.properties = properties;
    }

    @GetMapping({"/", "/console"})
    public String console(Model model) {
        model.addAttribute("apiBasePath", "");
        model.addAttribute("publicApiBaseUrl", properties.getPublicApiBaseUrl());
        return "console";
    }
}
