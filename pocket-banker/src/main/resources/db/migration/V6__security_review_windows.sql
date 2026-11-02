CREATE TABLE backend_model_signals (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(100) NOT NULL,
 disagreed BOOLEAN NOT NULL,rejected BOOLEAN NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_model_signals_user_time ON backend_model_signals(user_id,created_at);
CREATE TABLE backend_user_model_policy (
 user_id VARCHAR(100) PRIMARY KEY,policy_json VARCHAR(8000) NOT NULL,
 version BIGINT NOT NULL,updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
