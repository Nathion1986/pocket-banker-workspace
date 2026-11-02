ALTER TABLE perception_sources ADD COLUMN collector_report_json VARCHAR(2000);
ALTER TABLE perception_sources ADD COLUMN collector_report_hash VARCHAR(64);
ALTER TABLE perception_sources ADD COLUMN collector_received_at TIMESTAMP WITH TIME ZONE;
