-- Existing pending rows remain immediately eligible. Do not rewrite their body or creation time.
ALTER TABLE identity_lifecycle_outbox
 ADD COLUMN next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT '2000-01-01 00:00:00.000000';
CREATE INDEX idx_identity_lifecycle_outbox_due
 ON identity_lifecycle_outbox (status,next_attempt_at,created_at,id);
