CREATE TABLE gitops_changes (
    change_id VARCHAR(64) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    repository VARCHAR(255) NOT NULL,
    branch_name VARCHAR(255) NOT NULL,
    base_branch VARCHAR(255) NOT NULL,
    file_path VARCHAR(1000) NOT NULL,
    pull_request_number BIGINT,
    pull_request_url VARCHAR(2000),
    pull_request_state VARCHAR(32) NOT NULL,
    head_sha VARCHAR(128),
    deployment_state VARCHAR(32) NOT NULL,
    verification_result TEXT,
    rollback_reference VARCHAR(1000),
    error_message TEXT,
    requested_by VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    deployment_started_at TIMESTAMP(6),
    deployment_completed_at TIMESTAMP(6),
    PRIMARY KEY (change_id)
);

CREATE UNIQUE INDEX ux_gitops_changes_source
    ON gitops_changes(source_type, source_id, provider);
CREATE UNIQUE INDEX ux_gitops_changes_pull_request
    ON gitops_changes(provider, repository, pull_request_number);
CREATE INDEX ix_gitops_changes_state ON gitops_changes(pull_request_state, deployment_state);
CREATE INDEX ix_gitops_changes_created_at ON gitops_changes(created_at);

CREATE TABLE gitops_webhook_deliveries (
    delivery_id VARCHAR(128) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    received_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (delivery_id)
);

CREATE INDEX ix_gitops_webhook_deliveries_received_at
    ON gitops_webhook_deliveries(received_at);
