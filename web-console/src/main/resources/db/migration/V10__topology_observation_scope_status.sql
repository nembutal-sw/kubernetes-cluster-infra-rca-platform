ALTER TABLE topology_observations
    ADD COLUMN node_inventory_collected INTEGER NOT NULL DEFAULT 0;

ALTER TABLE topology_observations
    ADD COLUMN pod_inventory_collected INTEGER NOT NULL DEFAULT 0;
