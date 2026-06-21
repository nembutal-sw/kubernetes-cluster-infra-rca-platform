ALTER TABLE incidents ADD COLUMN node_names_json TEXT;

CREATE TABLE topology_observations (
    observation_id VARCHAR(64) PRIMARY KEY,
    cluster_id VARCHAR(64) NOT NULL,
    source_evidence_id VARCHAR(64) NOT NULL,
    source_node_name VARCHAR(255) NOT NULL,
    observed_at TIMESTAMP(6) NOT NULL,
    entities_json TEXT NOT NULL,
    relations_json TEXT NOT NULL,
    inventory_complete INTEGER NOT NULL,
    CONSTRAINT uq_topology_source_evidence UNIQUE (source_evidence_id),
    CONSTRAINT fk_topology_cluster FOREIGN KEY (cluster_id) REFERENCES clusters(cluster_id)
);

CREATE INDEX ix_topology_cluster_observed
    ON topology_observations(cluster_id, observed_at);
