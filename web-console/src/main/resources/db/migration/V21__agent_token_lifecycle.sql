ALTER TABLE node_agents ADD COLUMN node_token_rotated_at TIMESTAMP(6);
ALTER TABLE node_agents ADD COLUMN node_token_revoked_at TIMESTAMP(6);
ALTER TABLE node_agents ADD COLUMN next_node_token_hash VARCHAR(255);
ALTER TABLE node_agents ADD COLUMN next_node_token_expires_at TIMESTAMP(6);

UPDATE node_agents
SET node_token_rotated_at = registered_at
WHERE node_token_rotated_at IS NULL;
