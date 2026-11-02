CREATE TABLE perception_sources (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('SMS','EMAIL','LOCATION')),
    device_id VARCHAR(100),
    consent_id VARCHAR(100) NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    allowed_senders_json VARCHAR(4000) NOT NULL,
    connector_ref VARCHAR(100),
    status VARCHAR(16) NOT NULL CHECK (status IN ('active','revoked')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_source_user ON perception_sources(user_id, created_at);
CREATE TABLE perception_log (
    id VARCHAR(36) PRIMARY KEY,
    source_id VARCHAR(36) NOT NULL REFERENCES perception_sources(id),
    user_id VARCHAR(100) NOT NULL,
    external_id_hash VARCHAR(64) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('queued','processing','completed','filtered','needs_review','retry_required','revoked')),
    reason VARCHAR(80),
    attempts INTEGER NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_signal_source_external UNIQUE (source_id, external_id_hash)
);
CREATE INDEX idx_receipt_user ON perception_log(user_id, received_at);
CREATE INDEX idx_receipt_lease ON perception_log(status, lease_until);
CREATE TABLE events (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    receipt_id VARCHAR(36) NOT NULL REFERENCES perception_log(id),
    event_type VARCHAR(24) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    title VARCHAR(160) NOT NULL,
    amount NUMERIC(19,2),
    currency VARCHAR(3),
    due_date VARCHAR(10),
    recurrence VARCHAR(80),
    account_last4 VARCHAR(4),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    source VARCHAR(24) NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    extractor_version VARCHAR(40) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('candidate','confirmed','rejected','expired')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_event_receipt UNIQUE (receipt_id)
);
CREATE INDEX idx_events_user_status ON events(user_id, status, occurred_at);
CREATE TABLE event_decisions (
    id VARCHAR(36) PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    user_id VARCHAR(100) NOT NULL,
    decision VARCHAR(24) NOT NULL,
    decided_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE location_challenges (
    id VARCHAR(36) PRIMARY KEY,
    source_id VARCHAR(36) NOT NULL REFERENCES perception_sources(id),
    user_id VARCHAR(100) NOT NULL,
    device_id VARCHAR(100) NOT NULL,
    risk_event_id VARCHAR(36) NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('pending','resolved','unresolved','denied','low_accuracy','expired','revoked')),
    response_hash VARCHAR(64),
    city_code VARCHAR(40),
    city_name VARCHAR(100),
    collected_at TIMESTAMP WITH TIME ZONE,
    responded_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_challenge_user ON location_challenges(user_id, created_at);
CREATE TABLE mail_cursors (
    source_id VARCHAR(36) PRIMARY KEY REFERENCES perception_sources(id),
    scope_hash VARCHAR(64) NOT NULL,
    uid_validity BIGINT NOT NULL,
    last_uid BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
