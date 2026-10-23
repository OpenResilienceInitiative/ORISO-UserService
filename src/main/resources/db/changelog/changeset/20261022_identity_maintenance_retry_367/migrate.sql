-- Last attempt is recovery metadata, never authorization to perform a native deletion.
ALTER TABLE identity_maintenance_intent ADD COLUMN last_attempt_at DATETIME(6) NULL;
CREATE INDEX idx_identity_maintenance_retry ON identity_maintenance_intent(last_attempt_at, subject_id);
