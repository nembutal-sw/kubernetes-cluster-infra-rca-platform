CREATE TABLE manifest_download_tokens (
    token_id VARCHAR(64) PRIMARY KEY,
    cluster_id VARCHAR(64) NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    consumed_at TIMESTAMP(6),
    CONSTRAINT fk_manifest_token_cluster
        FOREIGN KEY (cluster_id) REFERENCES clusters(cluster_id),
    CONSTRAINT uq_manifest_download_token_hash UNIQUE (token_hash)
);

CREATE INDEX ix_manifest_tokens_cluster_expires
    ON manifest_download_tokens(cluster_id, expires_at);
