CREATE TABLE cluster_threshold_overrides (
    cluster_id VARCHAR(64) NOT NULL,
    threshold_key VARCHAR(128) NOT NULL,
    threshold_value DOUBLE PRECISION NOT NULL,
    reason TEXT,
    updated_by VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (cluster_id, threshold_key),
    CONSTRAINT fk_threshold_cluster FOREIGN KEY (cluster_id) REFERENCES clusters(cluster_id)
);

CREATE INDEX ix_cluster_threshold_overrides_cluster_id ON cluster_threshold_overrides(cluster_id);
