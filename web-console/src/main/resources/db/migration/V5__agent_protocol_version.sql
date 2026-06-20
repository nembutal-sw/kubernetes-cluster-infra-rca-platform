ALTER TABLE node_agents
    ADD COLUMN agent_protocol_version VARCHAR(32) NOT NULL DEFAULT '1';

CREATE INDEX ix_node_agents_protocol_version
    ON node_agents(agent_protocol_version);
