UPDATE action_executions
SET status = 'expired',
    error_message = 'Agent-side mutation execution was permanently disabled.',
    lease_owner = NULL,
    lease_expires_at = NULL,
    completed_at = CURRENT_TIMESTAMP
WHERE status IN ('pending_approval', 'queued', 'leased');

UPDATE action_requests
SET status = 'approved_manual'
WHERE status IN ('queued', 'executing');
