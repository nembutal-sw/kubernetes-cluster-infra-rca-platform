CREATE TABLE incidents (
    incident_id VARCHAR(64) PRIMARY KEY,
    dedup_key VARCHAR(128) NOT NULL,
    cluster_id VARCHAR(64) NOT NULL,
    node_name VARCHAR(255) NOT NULL,
    alert_name VARCHAR(255) NOT NULL,
    root_cause VARCHAR(1000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    occurrence_count INTEGER NOT NULL,
    first_seen_at TIMESTAMP(6) NOT NULL,
    last_seen_at TIMESTAMP(6) NOT NULL,
    latest_evidence_id VARCHAR(64),
    latest_report_id VARCHAR(64),
    CONSTRAINT uq_incidents_dedup_key UNIQUE (dedup_key),
    CONSTRAINT fk_incident_cluster FOREIGN KEY (cluster_id) REFERENCES clusters(cluster_id)
);
CREATE INDEX ix_incidents_cluster_id ON incidents(cluster_id);
CREATE INDEX ix_incidents_last_seen_at ON incidents(last_seen_at);
CREATE INDEX ix_incidents_status ON incidents(status);

ALTER TABLE rca_reports ADD COLUMN incident_id VARCHAR(64);
ALTER TABLE rca_reports
    ADD CONSTRAINT fk_report_incident FOREIGN KEY (incident_id) REFERENCES incidents(incident_id);
CREATE INDEX ix_rca_reports_incident_id ON rca_reports(incident_id);

CREATE TABLE action_requests (
    action_request_id VARCHAR(64) PRIMARY KEY,
    report_id VARCHAR(64) NOT NULL,
    action_index INTEGER NOT NULL,
    action_key VARCHAR(255) NOT NULL,
    policy VARCHAR(64) NOT NULL,
    source VARCHAR(64) NOT NULL,
    status VARCHAR(64) NOT NULL,
    requested_by VARCHAR(255) NOT NULL,
    reviewed_by VARCHAR(255),
    request_note TEXT,
    decision_note TEXT,
    evidence_request_id VARCHAR(64),
    created_at TIMESTAMP(6) NOT NULL,
    reviewed_at TIMESTAMP(6),
    CONSTRAINT fk_action_request_report FOREIGN KEY (report_id) REFERENCES rca_reports(report_id),
    CONSTRAINT fk_action_request_evidence_request
        FOREIGN KEY (evidence_request_id) REFERENCES evidence_requests(request_id)
);
CREATE INDEX ix_action_requests_report_id ON action_requests(report_id);
CREATE INDEX ix_action_requests_status ON action_requests(status);
CREATE INDEX ix_action_requests_created_at ON action_requests(created_at);

CREATE TABLE audit_events (
    audit_event_id VARCHAR(64) PRIMARY KEY,
    actor_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(255),
    outcome VARCHAR(32) NOT NULL,
    details_json TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX ix_audit_events_created_at ON audit_events(created_at);
CREATE INDEX ix_audit_events_actor_id ON audit_events(actor_id);
CREATE INDEX ix_audit_events_resource ON audit_events(resource_type, resource_id);
