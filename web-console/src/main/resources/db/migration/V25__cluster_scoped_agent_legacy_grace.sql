ALTER TABLE agent_enrollment_profiles
    ADD COLUMN legacy_token_grace_until TIMESTAMP(6);

CREATE INDEX ix_agent_enrollment_legacy_grace
    ON agent_enrollment_profiles(legacy_token_grace_until);
