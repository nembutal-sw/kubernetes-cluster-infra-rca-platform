CREATE INDEX ix_audit_events_actor_type ON audit_events(actor_type);
CREATE INDEX ix_audit_events_event_type ON audit_events(event_type);
CREATE INDEX ix_audit_events_outcome ON audit_events(outcome);
