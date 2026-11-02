ALTER TABLE backend_channel_links ADD COLUMN session_id VARCHAR(100) NOT NULL DEFAULT 'shared';
ALTER TABLE backend_channel_bindings ADD COLUMN session_id VARCHAR(100) NOT NULL DEFAULT 'shared';
UPDATE backend_channel_bindings b SET session_id=CONCAT('telegram-',external_id)
 WHERE channel='telegram' AND EXISTS (
  SELECT 1 FROM backend_sessions s WHERE s.user_id=b.user_id AND s.id=CONCAT('telegram-',b.external_id)
 );
ALTER TABLE backend_telegram_updates ADD COLUMN input_hash VARCHAR(64);
ALTER TABLE backend_telegram_updates ADD COLUMN result_cipher CLOB;
CREATE TABLE backend_channel_deliveries (
 id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(100) NOT NULL,
 channel VARCHAR(24) NOT NULL,external_id VARCHAR(100) NOT NULL,
 delivery_key VARCHAR(180) NOT NULL,source_type VARCHAR(24) NOT NULL,source_id VARCHAR(100),
 body_cipher CLOB NOT NULL,state VARCHAR(24) NOT NULL DEFAULT 'pending',
 attempts INTEGER NOT NULL DEFAULT 0,generation BIGINT NOT NULL DEFAULT 0,
 lease_until TIMESTAMP WITH TIME ZONE,next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
 external_message_id VARCHAR(100),error_code VARCHAR(80),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(user_id,channel,delivery_key)
);
CREATE INDEX idx_channel_delivery_due ON backend_channel_deliveries(source_type,state,next_attempt_at);
