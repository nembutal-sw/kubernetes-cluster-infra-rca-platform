package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgent;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentHeartbeatRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentRegisterRequest;
import io.clusterinfra.rca.webconsole.domain.RcaModels.NodeAgentRegistrationResponse;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class AgentRepository {
    private final JdbcRcaStore store;

    public AgentRepository(JdbcRcaStore store) {
        this.store = store;
    }

    public NodeAgentRegistrationResponse register(NodeAgentRegisterRequest request) {
        return store.registerAgent(request);
    }

    public Optional<NodeAgent> heartbeat(NodeAgentHeartbeatRequest request) {
        return store.recordAgentHeartbeat(request);
    }

    public List<NodeAgent> list(String clusterId) {
        return store.listAgents(clusterId);
    }

    public Optional<NodeAgent> find(String clusterId, String nodeName) {
        return store.getAgent(clusterId, nodeName);
    }

    public boolean verifyNodeToken(String clusterId, String nodeName, String nodeToken) {
        return store.verifyAgentNodeToken(clusterId, nodeName, nodeToken);
    }
}
