ALTER TABLE agent_enrollment_profiles
    ADD COLUMN reviewer_credential_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE agent_enrollment_profiles
    ADD COLUMN reviewer_previous_token_path VARCHAR(4096);
ALTER TABLE agent_enrollment_profiles
    ADD COLUMN reviewer_previous_valid_until TIMESTAMP(6);
ALTER TABLE agent_enrollment_profiles
    ADD COLUMN reviewer_credential_rotated_at TIMESTAMP(6);

CREATE INDEX ix_agent_enrollment_reviewer_previous_valid
    ON agent_enrollment_profiles(reviewer_previous_valid_until);
