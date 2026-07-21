ALTER TABLE gitops_changes ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE gitops_changes ADD COLUMN last_attempt_at TIMESTAMP(6);
ALTER TABLE gitops_changes ADD COLUMN last_failure_at TIMESTAMP(6);
ALTER TABLE gitops_changes ADD COLUMN last_reconciled_at TIMESTAMP(6);

UPDATE gitops_changes
SET last_attempt_at = created_at,
    last_failure_at = CASE WHEN pull_request_state = 'failed' THEN updated_at ELSE NULL END;
