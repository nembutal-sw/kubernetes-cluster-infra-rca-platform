package io.clusterinfra.rca.webconsole.controller;

import io.clusterinfra.rca.webconsole.domain.RcaModels.AgentHealthView;
import io.clusterinfra.rca.webconsole.persistence.AgentRepository;
import io.clusterinfra.rca.webconsole.persistence.ClusterRepository;
import io.clusterinfra.rca.webconsole.service.AgentHealthService;
import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
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

    @GetMapping("/clusters/{clusterId}/agent-health")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public List<AgentHealthView> list(@PathVariable String clusterId) {
        if (clusters.find(clusterId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "cluster not found");
        }
        return agents.list(clusterId).stream().map(health::classify).toList();
    }

    @GetMapping("/v1/agent-health")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public List<AgentHealthView> listAll(
        @RequestParam(name = "cluster_ids", required = false) String clusterIds
    ) {
        Set<String> requested = requestedClusterIds(clusterIds);
        return agents.listAll().stream()
            .filter(agent -> requested.isEmpty() || requested.contains(agent.clusterId()))
            .map(health::classify)
            .toList();
    }

    private Set<String> requestedClusterIds(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> result = Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .collect(Collectors.toUnmodifiableSet());
        if (result.size() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cluster_ids supports at most 100 values");
        }
        return result;
    }
}
