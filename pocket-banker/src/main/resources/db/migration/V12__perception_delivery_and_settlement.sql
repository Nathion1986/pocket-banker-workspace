CREATE TABLE backend_structured_jobs (
 id VARCHAR(36) PRIMARY KEY,
 source_id VARCHAR(36) NOT NULL REFERENCES perception_sources(id),
 user_id VARCHAR(100) NOT NULL,
 kind VARCHAR(24) NOT NULL,
 record_hash VARCHAR(64) NOT NULL,
 revision BIGINT NOT NULL,
 content_hash VARCHAR(64) NOT NULL,
 input_cipher CLOB NOT NULL,
 consent_epoch BIGINT NOT NULL,
 state VARCHAR(24) NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0,
 generation BIGINT NOT NULL DEFAULT 0,
 lease_until TIMESTAMP WITH TIME ZONE,
 next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
 result_cipher CLOB,
 error_code VARCHAR(80),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(source_id,kind,record_hash,revision)
);
CREATE INDEX idx_structured_due ON backend_structured_jobs(state,next_attempt_at);
CREATE TABLE backend_person_references (
 user_id VARCHAR(100) NOT NULL,
 person_id VARCHAR(36) NOT NULL,
 reference_type VARCHAR(16) NOT NULL,
 reference_id VARCHAR(36) NOT NULL,
 invalidated BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(user_id,person_id,reference_type,reference_id)
);
CREATE TABLE backend_person_edges (
 user_id VARCHAR(100) NOT NULL,
 person_id VARCHAR(36) NOT NULL,
 source_id VARCHAR(36) NOT NULL,
 summary_cipher CLOB NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(user_id,person_id)
);
ALTER TABLE backend_cashflow_occurrences ADD COLUMN paid_amount DECIMAL(19,2) NOT NULL DEFAULT 0;
UPDATE backend_cashflow_occurrences SET paid_amount=amount WHERE state='settled';
CREATE TABLE backend_cashflow_settlements (
 user_id VARCHAR(100) NOT NULL,
 identity_hash VARCHAR(64) NOT NULL,
 event_id VARCHAR(36) NOT NULL,
 due_date VARCHAR(10) NOT NULL,
 amount DECIMAL(19,2) NOT NULL,
 account_bank VARCHAR(16) NOT NULL,
 account_cipher CLOB NOT NULL,
 state VARCHAR(16) NOT NULL,
 verified_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(user_id,identity_hash)
);
ALTER TABLE backend_notifications ADD COLUMN delivery_generation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE backend_notifications ADD COLUMN delivery_lease_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE backend_notifications ADD COLUMN content_version BIGINT NOT NULL DEFAULT 0;

