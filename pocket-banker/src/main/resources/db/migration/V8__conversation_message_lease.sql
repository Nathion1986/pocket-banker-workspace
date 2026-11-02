ALTER TABLE backend_sessions ADD COLUMN active_message VARCHAR(160);
ALTER TABLE backend_sessions ADD COLUMN active_token VARCHAR(36);
ALTER TABLE backend_sessions ADD COLUMN active_lease_until TIMESTAMP WITH TIME ZONE;
