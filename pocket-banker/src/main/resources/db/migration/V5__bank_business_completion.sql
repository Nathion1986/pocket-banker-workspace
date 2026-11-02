ALTER TABLE bank_bindings ADD COLUMN scopes VARCHAR(100) NOT NULL DEFAULT 'read,write';
CREATE TABLE backend_payee_aliases (
 user_id VARCHAR(100) NOT NULL,person_id VARCHAR(36) NOT NULL REFERENCES backend_persons(id) ON DELETE CASCADE,
 alias_hash VARCHAR(64) NOT NULL,alias_cipher CLOB NOT NULL,kind VARCHAR(16) NOT NULL,
 PRIMARY KEY(user_id,person_id,alias_hash)
);
CREATE INDEX idx_payee_alias ON backend_payee_aliases(user_id,alias_hash);
CREATE TABLE backend_aa_invitations (
 id VARCHAR(36) PRIMARY KEY,order_id VARCHAR(36) NOT NULL REFERENCES backend_aa_orders(id) ON DELETE CASCADE,
 person_id VARCHAR(36) NOT NULL,owner_id VARCHAR(100) NOT NULL,recipient_user_id VARCHAR(100),
 token_hash VARCHAR(64) NOT NULL,token_cipher CLOB NOT NULL,state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,UNIQUE(order_id,person_id)
);
