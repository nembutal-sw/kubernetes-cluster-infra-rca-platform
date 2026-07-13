CREATE INDEX ix_rca_reports_page ON rca_reports(created_at, report_id);
CREATE INDEX ix_incidents_page ON incidents(last_seen_at, incident_id);
CREATE INDEX ix_analysis_tasks_page ON rca_analysis_tasks(created_at, task_id);
