ALTER TABLE agent_enrollment_profiles
    ADD COLUMN profile_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE agent_enrollment_profiles
    ADD COLUMN reviewer_token_path VARCHAR(4096);
ALTER TABLE agent_enrollment_profiles
    ADD COLUMN expected_service_account_uid VARCHAR(255);
ALTER TABLE agent_enrollment_profiles
    ADD COLUMN expected_daemonset_name VARCHAR(253);
ALTER TABLE agent_enrollment_profiles
    ADD COLUMN expected_daemonset_uid VARCHAR(255);
ALTER TABLE agent_enrollment_profiles
    ADD COLUMN required_pod_labels_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE agent_enrollment_profiles
    ADD COLUMN allowed_image_digest VARCHAR(71);

ALTER TABLE node_agents
    ADD COLUMN enrollment_profile_version BIGINT;
ALTER TABLE node_agents
    ADD COLUMN enrollment_service_account_uid VARCHAR(255);
ALTER TABLE node_agents
    ADD COLUMN enrollment_daemonset_uid VARCHAR(255);

CREATE INDEX ix_node_agents_enrollment_profile
    ON node_agents(cluster_id, enrollment_profile_version);
