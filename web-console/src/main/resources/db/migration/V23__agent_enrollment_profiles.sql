CREATE TABLE agent_enrollment_profiles (
    cluster_id VARCHAR(64) PRIMARY KEY,
    mode VARCHAR(64) NOT NULL,
    api_server_url VARCHAR(2048) NOT NULL,
    ca_bundle_pem TEXT NOT NULL,
    ca_sha256 VARCHAR(64) NOT NULL,
    audience VARCHAR(255) NOT NULL,
    service_account_namespace VARCHAR(63) NOT NULL,
    service_account_name VARCHAR(253) NOT NULL,
    bootstrap_fallback_allowed BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_agent_enrollment_cluster
        FOREIGN KEY (cluster_id) REFERENCES clusters(cluster_id) ON DELETE CASCADE
);

CREATE INDEX ix_agent_enrollment_profiles_mode
    ON agent_enrollment_profiles(mode, updated_at);
