package io.clusterinfra.rca.webconsole.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.clusterinfra.rca.webconsole.persistence.AgentEnrollmentRepository.AgentEnrollmentConfiguration;

public interface KubernetesApiTransport {
    JsonNode reviewToken(AgentEnrollmentConfiguration configuration, String token);

    JsonNode pod(
        AgentEnrollmentConfiguration configuration,
        String namespace,
        String podName
    );
}
