package io.clusterinfra.rca.webconsole.persistence;

import io.clusterinfra.rca.webconsole.domain.RcaModels.Cluster;
import io.clusterinfra.rca.webconsole.domain.RcaModels.ClusterCreateRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class ClusterRepository {
    private final JdbcRcaStore store;

    public ClusterRepository(JdbcRcaStore store) {
        this.store = store;
    }

    public Cluster create(ClusterCreateRequest request) {
        return store.createCluster(request);
    }

    public List<Cluster> list() {
        return store.listClusters();
    }

    public Optional<Cluster> find(String clusterId) {
        return store.getCluster(clusterId);
    }

    public boolean delete(String clusterId) {
        return store.deleteCluster(clusterId);
    }

    public Cluster rotateBootstrapToken(String clusterId) {
        return store.rotateClusterBootstrapToken(clusterId);
    }
}
