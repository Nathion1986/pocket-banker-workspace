CREATE TABLE auth_accounts (
  user_id VARCHAR(100) PRIMARY KEY,
  username_normalized VARCHAR(100) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'active',
  auth_epoch BIGINT NOT NULL DEFAULT 1,
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until TIMESTAMP WITH TIME ZONE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE auth_sessions (
  id VARCHAR(36) PRIMARY KEY,
  user_id VARCHAR(100) NOT NULL REFERENCES auth_accounts(user_id),
  access_hash CHAR(64) NOT NULL UNIQUE,
  access_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'active',
  auth_epoch BIGINT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX auth_sessions_user ON auth_sessions(user_id);
CREATE TABLE auth_access_history (
  token_hash CHAR(64) PRIMARY KEY,
  session_id VARCHAR(36) NOT NULL REFERENCES auth_sessions(id)
);
CREATE TABLE auth_refresh_tokens (
  token_hash CHAR(64) PRIMARY KEY,
  session_id VARCHAR(36) NOT NULL REFERENCES auth_sessions(id),
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  used_at TIMESTAMP WITH TIME ZONE,
  replaced_by CHAR(64),
  revoked_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE auth_login_challenges (
  id CHAR(64) PRIMARY KEY,
  user_id VARCHAR(100) NOT NULL REFERENCES auth_accounts(user_id),
  auth_epoch BIGINT NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  used_at TIMESTAMP WITH TIME ZONE,
  failures INT NOT NULL DEFAULT 0
);
CREATE TABLE auth_totp_factors (
  user_id VARCHAR(100) PRIMARY KEY REFERENCES auth_accounts(user_id),
  secret_cipher TEXT NOT NULL,
  state VARCHAR(16) NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE,
  last_accepted_step BIGINT NOT NULL DEFAULT -1,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE auth_recovery_codes (
  code_hash CHAR(64) PRIMARY KEY,
  user_id VARCHAR(100) NOT NULL REFERENCES auth_accounts(user_id),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  used_at TIMESTAMP WITH TIME ZONE,
  revoked_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE auth_operation_challenges (
  id VARCHAR(36) PRIMARY KEY,
  user_id VARCHAR(100) NOT NULL REFERENCES auth_accounts(user_id),
  session_id VARCHAR(36) NOT NULL REFERENCES auth_sessions(id),
  conversation_id VARCHAR(100) NOT NULL,
  task_id VARCHAR(36) NOT NULL,
  task_version BIGINT NOT NULL,
  operation_digest CHAR(64) NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'pending',
  failure_count INT NOT NULL DEFAULT 0
);
