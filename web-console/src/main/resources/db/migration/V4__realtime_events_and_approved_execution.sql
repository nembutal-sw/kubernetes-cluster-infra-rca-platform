CREATE TABLE action_executions (
    execution_id VARCHAR(64) PRIMARY KEY,
    action_request_id VARCHAR(64) NOT NULL,
    report_id VARCHAR(64) NOT NULL,
    cluster_id VARCHAR(64) NOT NULL,
    node_name VARCHAR(255) NOT NULL,
    action_key VARCHAR(255) NOT NULL,
    command_key VARCHAR(255) NOT NULL,
    parameters_json TEXT NOT NULL,
    preview_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    timeout_seconds INTEGER NOT NULL,
    requested_by VARCHAR(255) NOT NULL,
    approved_by VARCHAR(255),
    lease_owner VARCHAR(255),
    lease_expires_at TIMESTAMP(6),
    exit_code INTEGER,
    stdout_text TEXT,
    stderr_text TEXT,
    error_message TEXT,
    created_at TIMESTAMP(6) NOT NULL,
    approved_at TIMESTAMP(6),
    started_at TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    CONSTRAINT uq_action_execution_request UNIQUE (action_request_id),
    CONSTRAINT fk_action_execution_request FOREIGN KEY (action_request_id)
        REFERENCES action_requests(action_request_id),
    CONSTRAINT fk_action_execution_report FOREIGN KEY (report_id) REFERENCES rca_reports(report_id),
    CONSTRAINT fk_action_execution_cluster FOREIGN KEY (cluster_id) REFERENCES clusters(cluster_id)
);
CREATE INDEX ix_action_executions_status ON action_executions(status, created_at);
CREATE INDEX ix_action_executions_node ON action_executions(cluster_id, node_name, status);
CREATE INDEX ix_action_executions_report ON action_executions(report_id);

CREATE TABLE realtime_events (
    event_id VARCHAR(64) PRIMARY KEY,
    evidence_id VARCHAR(64) NOT NULL,
    cluster_id VARCHAR(64) NOT NULL,
    node_name VARCHAR(255) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    component VARCHAR(64) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    observed_at TIMESTAMP(6) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_realtime_event_evidence FOREIGN KEY (evidence_id)
        REFERENCES evidence_bundles(evidence_id),
    CONSTRAINT fk_realtime_event_cluster FOREIGN KEY (cluster_id) REFERENCES clusters(cluster_id)
);
CREATE INDEX ix_realtime_events_node_time ON realtime_events(cluster_id, node_name, observed_at);
CREATE INDEX ix_realtime_events_type_time ON realtime_events(event_type, observed_at);
