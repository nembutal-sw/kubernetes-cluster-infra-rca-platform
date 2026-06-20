CREATE TABLE rca_analysis_tasks (
    task_id VARCHAR(64) PRIMARY KEY,
    evidence_id VARCHAR(64) NOT NULL,
    cluster_id VARCHAR(64) NOT NULL,
    node_name VARCHAR(255) NOT NULL,
    alert_name VARCHAR(255) NOT NULL,
    source VARCHAR(64) NOT NULL,
    skip_if_healthy INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL,
    max_attempts INTEGER NOT NULL,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    lease_owner VARCHAR(255),
    lease_expires_at TIMESTAMP(6),
    last_error TEXT,
    report_id VARCHAR(64),
    job_id VARCHAR(64),
    created_at TIMESTAMP(6) NOT NULL,
    started_at TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    CONSTRAINT uq_analysis_tasks_evidence UNIQUE (evidence_id),
    CONSTRAINT fk_analysis_task_evidence FOREIGN KEY (evidence_id) REFERENCES evidence_bundles(evidence_id),
    CONSTRAINT fk_analysis_task_cluster FOREIGN KEY (cluster_id) REFERENCES clusters(cluster_id),
    CONSTRAINT fk_analysis_task_report FOREIGN KEY (report_id) REFERENCES rca_reports(report_id),
    CONSTRAINT fk_analysis_task_job FOREIGN KEY (job_id) REFERENCES rca_jobs(job_id)
);
CREATE INDEX ix_analysis_tasks_status_next ON rca_analysis_tasks(status, next_attempt_at);
CREATE INDEX ix_analysis_tasks_lease ON rca_analysis_tasks(status, lease_expires_at);
CREATE INDEX ix_analysis_tasks_cluster ON rca_analysis_tasks(cluster_id);
CREATE INDEX ix_analysis_tasks_created_at ON rca_analysis_tasks(created_at);
