CREATE TABLE notification_outbox (
    event_id VARCHAR(64) PRIMARY KEY,
    idempotency_key VARCHAR(190) NOT NULL,
    incident_id VARCHAR(64) NOT NULL,
    report_id VARCHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMP(6),
    last_status_code INTEGER,
    last_error VARCHAR(2000),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    delivered_at TIMESTAMP(6),
    CONSTRAINT uq_notification_outbox_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX ix_notification_outbox_claim
    ON notification_outbox(status, next_attempt_at, created_at);
CREATE INDEX ix_notification_outbox_incident
    ON notification_outbox(incident_id, created_at);
CREATE INDEX ix_notification_outbox_report
    ON notification_outbox(report_id, created_at);
