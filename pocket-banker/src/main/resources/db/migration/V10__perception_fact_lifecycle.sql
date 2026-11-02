-- Only structured references and encrypted business summaries; never raw source messages.
ALTER TABLE perception_sources ADD COLUMN paused BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE perception_sources ADD COLUMN consent_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE perception_log ADD COLUMN consent_epoch BIGINT NOT NULL DEFAULT 0;
CREATE TABLE backend_advice_items (
 user_id VARCHAR(100) NOT NULL,
 delivery_key VARCHAR(250) NOT NULL,
 notification_id VARCHAR(36) NOT NULL REFERENCES backend_notifications(id) ON DELETE CASCADE,
 PRIMARY KEY(user_id, delivery_key)
);
ALTER TABLE events ADD COLUMN remind_after TIMESTAMP WITH TIME ZONE;
ALTER TABLE location_challenges ADD COLUMN result_expires_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE location_challenges ADD COLUMN boundary_version VARCHAR(100);
CREATE TABLE backend_calendar_resources (
 source_id VARCHAR(36) NOT NULL REFERENCES perception_sources(id),
 resource_hash VARCHAR(64) NOT NULL,
 user_id VARCHAR(100) NOT NULL,
 revision BIGINT NOT NULL,
 content_hash VARCHAR(64) NOT NULL,
 members_json CLOB NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(source_id, resource_hash)
);
CREATE TABLE backend_cashflow_occurrences (
 user_id VARCHAR(100) NOT NULL,
 event_id VARCHAR(36) NOT NULL,
 due_date VARCHAR(10) NOT NULL,
 amount DECIMAL(19,2) NOT NULL,
 currency VARCHAR(3) NOT NULL,
 state VARCHAR(24) NOT NULL CHECK(state IN ('expected','settled','invalidated')),
 payment_task_id VARCHAR(36),
 settlement_operation VARCHAR(100) UNIQUE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 settled_at TIMESTAMP WITH TIME ZONE,
 PRIMARY KEY(user_id,event_id,due_date)
);
CREATE TABLE backend_caldav_resources (
 source_id VARCHAR(36) NOT NULL REFERENCES perception_sources(id),
 resource_hash VARCHAR(64) NOT NULL,
 etag_hash VARCHAR(64) NOT NULL,
 revision BIGINT NOT NULL,
 deleted BOOLEAN NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(source_id, resource_hash)
);
