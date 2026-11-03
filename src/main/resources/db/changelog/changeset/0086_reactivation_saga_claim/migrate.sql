ALTER TABLE user
  ADD COLUMN IF NOT EXISTS reactivation_operation_id VARCHAR(36) NULL,
  ADD COLUMN IF NOT EXISTS reactivation_operation_started_at DATETIME NULL;
