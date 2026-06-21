ALTER TABLE incidents ADD COLUMN resolved_at TIMESTAMP(6);
ALTER TABLE incidents ADD COLUMN resolution_source VARCHAR(32);
ALTER TABLE incidents ADD COLUMN resolution_note VARCHAR(1000);
ALTER TABLE incidents ADD COLUMN recurrence_of_incident_id VARCHAR(64);
ALTER TABLE incidents ADD COLUMN recurrence_sequence INTEGER NOT NULL DEFAULT 0;

ALTER TABLE incidents
    ADD CONSTRAINT fk_incident_recurrence
    FOREIGN KEY (recurrence_of_incident_id) REFERENCES incidents(incident_id);

CREATE INDEX ix_incidents_resolved_at ON incidents(resolved_at);
CREATE INDEX ix_incidents_recurrence ON incidents(recurrence_of_incident_id);

UPDATE incidents
SET resolved_at = last_seen_at,
    resolution_source = 'legacy'
WHERE status = 'resolved' AND resolved_at IS NULL;
