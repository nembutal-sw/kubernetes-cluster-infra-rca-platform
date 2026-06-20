package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentHealthView;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.service.AgentHealthService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/clusters/{clusterId}/agent-health")
public class AgentHealthController {
    private final ClusterRepository clusters;
    private final AgentRepository agents;
    private final AgentHealthService health;

    public AgentHealthController(
        ClusterRepository clusters,
        AgentRepository agents,
        AgentHealthService health
    ) {
        this.clusters = clusters;
        this.agents = agents;
        this.health = health;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public List<AgentHealthView> list(@PathVariable String clusterId) {
        if (clusters.find(clusterId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "cluster not found");
        }
        return agents.list(clusterId).stream().map(health::classify).toList();
    }
}
