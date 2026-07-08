CREATE TABLE catalog_override_drafts (
    draft_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    override_json TEXT NOT NULL,
    preview_summary_json TEXT NOT NULL,
    diff_json TEXT NOT NULL,
    diff_truncated INTEGER NOT NULL,
    validation_message TEXT,
    reason TEXT,
    requested_by VARCHAR(255),
    reviewed_by VARCHAR(255),
    decision_note TEXT,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    reviewed_at TIMESTAMP(6),
    PRIMARY KEY (draft_id)
);

CREATE INDEX ix_catalog_override_drafts_status ON catalog_override_drafts(status);
CREATE INDEX ix_catalog_override_drafts_created_at ON catalog_override_drafts(created_at);
