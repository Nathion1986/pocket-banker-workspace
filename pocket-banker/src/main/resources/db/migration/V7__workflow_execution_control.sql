ALTER TABLE backend_tasks ADD COLUMN desired_control VARCHAR(16) NOT NULL DEFAULT 'RUN';
ALTER TABLE backend_nodes ADD COLUMN dispatch_started BOOLEAN NOT NULL DEFAULT FALSE;
-- Existing in-flight rows predate the send marker; preserve their unknown external outcome.
UPDATE backend_nodes SET dispatch_started=TRUE WHERE state IN ('EXECUTING','RECONCILING','BANK_PENDING','SUCCEEDED')
 OR (state='MANUAL_REQUIRED' AND (error_code IS NULL OR error_code<>'model_review_not_confirmed'));
CREATE TABLE backend_compensations (
 task_id VARCHAR(36) NOT NULL REFERENCES backend_tasks(id) ON DELETE CASCADE,
 node_id VARCHAR(100) NOT NULL, operation_key VARCHAR(180) NOT NULL UNIQUE,
 state VARCHAR(32) NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
 generation BIGINT NOT NULL DEFAULT 0, lease_until TIMESTAMP WITH TIME ZONE,
 next_attempt_at TIMESTAMP WITH TIME ZONE, window_until TIMESTAMP WITH TIME ZONE,
 error_code VARCHAR(100), PRIMARY KEY(task_id,node_id)
);
CREATE INDEX idx_compensation_due ON backend_compensations(state,next_attempt_at);
CREATE TABLE backend_trigger_runs (
 template_id VARCHAR(36) NOT NULL REFERENCES backend_tasks(id) ON DELETE CASCADE,
 occurrence_key VARCHAR(160) NOT NULL, task_id VARCHAR(36) NOT NULL,
 PRIMARY KEY(template_id,occurrence_key)
);
