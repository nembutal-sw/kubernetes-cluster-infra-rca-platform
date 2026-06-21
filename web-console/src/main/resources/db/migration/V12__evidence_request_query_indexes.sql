CREATE INDEX ix_evidence_requests_cluster_created
    ON evidence_requests(cluster_id, created_at);

CREATE INDEX ix_evidence_requests_cluster_node_status_created
    ON evidence_requests(cluster_id, node_name, status, created_at);
