CREATE TABLE backend_users (
 user_id VARCHAR(100) PRIMARY KEY, failures INTEGER NOT NULL DEFAULT 0,
 locked_until TIMESTAMP WITH TIME ZONE, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE bank_bindings (
 user_id VARCHAR(100) NOT NULL, bank VARCHAR(16) NOT NULL, account_id VARCHAR(160) NOT NULL,
 state VARCHAR(16) NOT NULL, consent_id VARCHAR(100) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(user_id,bank,account_id)
);
CREATE TABLE backend_sessions (
 id VARCHAR(100) NOT NULL,user_id VARCHAR(100) NOT NULL,context_cipher CLOB NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,version BIGINT NOT NULL,
 PRIMARY KEY(user_id,id)
);
CREATE TABLE backend_messages (
 id VARCHAR(160) NOT NULL,user_id VARCHAR(100) NOT NULL,session_id VARCHAR(100) NOT NULL,
 input_hash VARCHAR(64) NOT NULL,reply_json CLOB,state VARCHAR(24) NOT NULL,created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(user_id,id)
);
CREATE TABLE backend_tasks (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(100) NOT NULL,request_key VARCHAR(160) NOT NULL,
 input_hash VARCHAR(64) NOT NULL,plan_cipher CLOB NOT NULL,state VARCHAR(32) NOT NULL,
 required_risk VARCHAR(16) NOT NULL,approved_risk VARCHAR(16),approved_version BIGINT,
 authorization_until TIMESTAMP WITH TIME ZONE,challenge_id VARCHAR(36),challenge_hash VARCHAR(64),
 second_factor_hash VARCHAR(64),passed_factors INTEGER NOT NULL DEFAULT 0,verify_failures INTEGER NOT NULL DEFAULT 0,
 trigger_type VARCHAR(16) NOT NULL,trigger_value VARCHAR(300),next_run_at TIMESTAMP WITH TIME ZONE,
 parent_id VARCHAR(36),run_number BIGINT NOT NULL DEFAULT 0,source_event_id VARCHAR(36),
 version BIGINT NOT NULL,created_at TIMESTAMP WITH TIME ZONE NOT NULL,updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,model_degraded BOOLEAN NOT NULL DEFAULT FALSE,
 CONSTRAINT uq_backend_request UNIQUE(user_id,request_key)
);
CREATE INDEX idx_task_due ON backend_tasks(state,next_run_at);
CREATE TABLE backend_nodes (
 id VARCHAR(100) NOT NULL,task_id VARCHAR(36) NOT NULL REFERENCES backend_tasks(id) ON DELETE CASCADE,
 action_id VARCHAR(80) NOT NULL,input_cipher CLOB NOT NULL,depends_json VARCHAR(2000) NOT NULL,
 state VARCHAR(32) NOT NULL,operation_key VARCHAR(180) NOT NULL UNIQUE,
 output_cipher CLOB,attempts INTEGER NOT NULL DEFAULT 0,lease_until TIMESTAMP WITH TIME ZONE,
 next_attempt_at TIMESTAMP WITH TIME ZONE,generation BIGINT NOT NULL DEFAULT 0,error_code VARCHAR(100),
 PRIMARY KEY(task_id,id)
);
CREATE TABLE backend_attempts (
 id VARCHAR(36) PRIMARY KEY,task_id VARCHAR(36) NOT NULL,node_id VARCHAR(100) NOT NULL,
 attempt_number INTEGER NOT NULL,state VARCHAR(32) NOT NULL,error_code VARCHAR(100),
 started_at TIMESTAMP WITH TIME ZONE NOT NULL,finished_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE quota_reservations (
 operation_key VARCHAR(180) PRIMARY KEY,user_id VARCHAR(100) NOT NULL,business_date VARCHAR(10) NOT NULL,
 amount NUMERIC(19,2) NOT NULL,state VARCHAR(16) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_quota_user_day ON quota_reservations(user_id,business_date,state);
CREATE TABLE backend_audit (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(100) NOT NULL,task_id VARCHAR(36),node_id VARCHAR(100),
 type VARCHAR(60) NOT NULL,detail_json CLOB NOT NULL,created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_audit_user ON backend_audit(user_id,created_at);
CREATE TABLE backend_persons (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(100) NOT NULL,name_cipher CLOB NOT NULL,
 relation VARCHAR(40) NOT NULL,birthday VARCHAR(10),city VARCHAR(100),
 bank VARCHAR(16),account_cipher CLOB,confirmed BOOLEAN NOT NULL,source_event_id VARCHAR(36),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE backend_relations (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(100) NOT NULL,from_id VARCHAR(36) NOT NULL,to_id VARCHAR(36) NOT NULL,
 kind VARCHAR(40) NOT NULL,amount NUMERIC(19,2),state VARCHAR(24) NOT NULL,evidence_event_id VARCHAR(36)
);
CREATE TABLE backend_facts (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(100) NOT NULL,subject_id VARCHAR(36),fact_key VARCHAR(80) NOT NULL,
 value_cipher CLOB NOT NULL,kind VARCHAR(24) NOT NULL,valid_from TIMESTAMP WITH TIME ZONE NOT NULL,
 valid_to TIMESTAMP WITH TIME ZONE,evidence_json VARCHAR(4000) NOT NULL,updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(user_id,subject_id,fact_key)
);
CREATE TABLE backend_tags (
 user_id VARCHAR(100) NOT NULL,tag VARCHAR(100) NOT NULL,confidence DOUBLE PRECISION NOT NULL,
 evidence_json VARCHAR(4000) NOT NULL,updated_at TIMESTAMP WITH TIME ZONE NOT NULL,PRIMARY KEY(user_id,tag)
);
CREATE TABLE backend_payee_stats (
 user_id VARCHAR(100) NOT NULL,bank VARCHAR(16) NOT NULL,account_hash VARCHAR(64) NOT NULL,
 count INTEGER NOT NULL,amount NUMERIC(19,2) NOT NULL,last_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(user_id,bank,account_hash)
);
CREATE TABLE backend_proposals (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(100) NOT NULL,source_event_id VARCHAR(36),
 kind VARCHAR(40) NOT NULL,plan_cipher CLOB NOT NULL,explanation VARCHAR(500) NOT NULL,
 state VARCHAR(24) NOT NULL,expires_at TIMESTAMP WITH TIME ZONE NOT NULL,task_id VARCHAR(36),created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE backend_notifications (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(100) NOT NULL,delivery_key VARCHAR(180) NOT NULL UNIQUE,
 kind VARCHAR(40) NOT NULL,content_cipher CLOB NOT NULL,state VARCHAR(24) NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0,next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
 acknowledged_at TIMESTAMP WITH TIME ZONE,created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE backend_channel_bindings (
 user_id VARCHAR(100) NOT NULL,channel VARCHAR(24) NOT NULL,external_id VARCHAR(100) NOT NULL,
 state VARCHAR(16) NOT NULL,created_at TIMESTAMP WITH TIME ZONE NOT NULL,PRIMARY KEY(channel,external_id)
);
CREATE TABLE backend_channel_links (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(100) NOT NULL,token_hash VARCHAR(64) NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,consumed BOOLEAN NOT NULL
);
CREATE TABLE backend_aa_orders (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(100) NOT NULL,operation_key VARCHAR(180) NOT NULL UNIQUE,
 total NUMERIC(19,2) NOT NULL,currency VARCHAR(3) NOT NULL,state VARCHAR(24) NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE backend_aa_members (
 order_id VARCHAR(36) NOT NULL REFERENCES backend_aa_orders(id) ON DELETE CASCADE,
 person_id VARCHAR(36) NOT NULL,amount NUMERIC(19,2) NOT NULL,state VARCHAR(24) NOT NULL,
 payment_reference VARCHAR(160),PRIMARY KEY(order_id,person_id)
);
CREATE UNIQUE INDEX uq_aa_payment_reference ON backend_aa_members(payment_reference);
CREATE TABLE backend_shadow_sessions (
 id VARCHAR(100) NOT NULL,user_id VARCHAR(100) NOT NULL,factor INTEGER NOT NULL,date_shift INTEGER NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,PRIMARY KEY(user_id,id)
);
CREATE TABLE backend_shadow_maps (
 user_id VARCHAR(100) NOT NULL,session_id VARCHAR(100) NOT NULL,token VARCHAR(40) NOT NULL,
 value_cipher CLOB NOT NULL,kind VARCHAR(24) NOT NULL,PRIMARY KEY(user_id,session_id,token)
);
CREATE TABLE backend_model_policy (
 id INTEGER PRIMARY KEY,policy_json VARCHAR(8000) NOT NULL,version BIGINT NOT NULL,updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE backend_telegram_updates (
 update_id BIGINT PRIMARY KEY,user_id VARCHAR(100),state VARCHAR(24) NOT NULL,created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
ALTER TABLE events ALTER COLUMN receipt_id DROP NOT NULL;
ALTER TABLE events ADD COLUMN subject_id VARCHAR(36);
ALTER TABLE events ADD COLUMN payload_json CLOB;
ALTER TABLE events ADD COLUMN task_id VARCHAR(36);
ALTER TABLE events ADD COLUMN superseded_by VARCHAR(36);
CREATE UNIQUE INDEX uq_event_task_result ON events(task_id);
