CREATE TABLE clusters (
    cluster_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    environment VARCHAR(64) NOT NULL,
    description TEXT,
    status VARCHAR(32) NOT NULL,
    bootstrap_token VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    last_seen_at TIMESTAMP(6)
);

CREATE TABLE evidence_bundles (
    evidence_id VARCHAR(64) PRIMARY KEY,
    cluster_id VARCHAR(64) NOT NULL,
    node_name VARCHAR(255) NOT NULL,
    alert_name VARCHAR(255) NOT NULL,
    collectors_json TEXT NOT NULL,
    collected_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_evidence_cluster FOREIGN KEY (cluster_id) REFERENCES clusters(cluster_id)
);
CREATE INDEX ix_evidence_bundles_cluster_id ON evidence_bundles(cluster_id);

CREATE TABLE node_agents (
    agent_id VARCHAR(64) PRIMARY KEY,
    cluster_id VARCHAR(64) NOT NULL,
    node_name VARCHAR(255) NOT NULL,
    node_token_hash VARCHAR(512) NOT NULL,
    agent_version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    supported_collectors_json TEXT NOT NULL,
    metadata_json TEXT NOT NULL,
    health_json TEXT NOT NULL,
    registered_at TIMESTAMP(6) NOT NULL,
    last_heartbeat_at TIMESTAMP(6),
    CONSTRAINT fk_agent_cluster FOREIGN KEY (cluster_id) REFERENCES clusters(cluster_id),
    CONSTRAINT uq_node_agents_cluster_node UNIQUE (cluster_id, node_name)
);
CREATE INDEX ix_node_agents_cluster_id ON node_agents(cluster_id);

CREATE TABLE user_accounts (
    user_id VARCHAR(64) PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    password_hash VARCHAR(512) NOT NULL,
    requested_role VARCHAR(32) NOT NULL,
    role VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    reason TEXT,
    approval_note TEXT,
    approved_by VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL,
    approved_at TIMESTAMP(6),
    CONSTRAINT uq_user_accounts_email UNIQUE (email)
);
CREATE INDEX ix_user_accounts_email ON user_accounts(email);
CREATE INDEX ix_user_accounts_status ON user_accounts(status);

CREATE TABLE user_sessions (
    session_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6),
    CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES user_accounts(user_id),
    CONSTRAINT uq_user_sessions_token_hash UNIQUE (token_hash)
);
CREATE INDEX ix_user_sessions_user_id ON user_sessions(user_id);
CREATE INDEX ix_user_sessions_token_hash ON user_sessions(token_hash);
CREATE INDEX ix_user_sessions_expires_at ON user_sessions(expires_at);

CREATE TABLE evidence_requests (
    request_id VARCHAR(64) PRIMARY KEY,
    cluster_id VARCHAR(64) NOT NULL,
    node_name VARCHAR(255) NOT NULL,
    alert_name VARCHAR(255) NOT NULL,
    requested_collectors_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    time_range_json TEXT NOT NULL,
    reason TEXT,
    context_json TEXT NOT NULL,
    evidence_id VARCHAR(64),
    error_message TEXT,
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    CONSTRAINT fk_request_cluster FOREIGN KEY (cluster_id) REFERENCES clusters(cluster_id),
    CONSTRAINT fk_request_evidence FOREIGN KEY (evidence_id) REFERENCES evidence_bundles(evidence_id)
);
CREATE INDEX ix_evidence_requests_cluster_id ON evidence_requests(cluster_id);
CREATE INDEX ix_evidence_requests_evidence_id ON evidence_requests(evidence_id);
CREATE INDEX ix_evidence_requests_node_name ON evidence_requests(node_name);
CREATE INDEX ix_evidence_requests_status ON evidence_requests(status);

CREATE TABLE rca_reports (
    report_id VARCHAR(64) PRIMARY KEY,
    cluster_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    trigger_json TEXT NOT NULL,
    scope_json TEXT NOT NULL,
    summary_json TEXT NOT NULL,
    evidence_json TEXT NOT NULL,
    root_cause_candidates_json TEXT NOT NULL,
    recommended_actions_json TEXT NOT NULL,
    policy_decisions_json TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_report_cluster FOREIGN KEY (cluster_id) REFERENCES clusters(cluster_id)
);
CREATE INDEX ix_rca_reports_cluster_id ON rca_reports(cluster_id);

CREATE TABLE rca_jobs (
    job_id VARCHAR(64) PRIMARY KEY,
    cluster_id VARCHAR(64) NOT NULL,
    alert_name VARCHAR(255) NOT NULL,
    node_name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    report_id VARCHAR(64) NOT NULL,
    evidence_id VARCHAR(64),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_job_cluster FOREIGN KEY (cluster_id) REFERENCES clusters(cluster_id),
    CONSTRAINT fk_job_report FOREIGN KEY (report_id) REFERENCES rca_reports(report_id),
    CONSTRAINT fk_job_evidence FOREIGN KEY (evidence_id) REFERENCES evidence_bundles(evidence_id)
);
CREATE INDEX ix_rca_jobs_cluster_id ON rca_jobs(cluster_id);
CREATE INDEX ix_rca_jobs_report_id ON rca_jobs(report_id);
CREATE INDEX ix_rca_jobs_evidence_id ON rca_jobs(evidence_id);
