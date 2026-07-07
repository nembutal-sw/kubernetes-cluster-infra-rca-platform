ALTER TABLE clusters ADD COLUMN bootstrap_token_hash VARCHAR(512);

ALTER TABLE clusters ADD COLUMN bootstrap_token_last_used_at TIMESTAMP(6);

ALTER TABLE clusters ADD COLUMN bootstrap_token_rotated_at TIMESTAMP(6);

ALTER TABLE clusters ADD COLUMN bootstrap_token_revoked_at TIMESTAMP(6);
