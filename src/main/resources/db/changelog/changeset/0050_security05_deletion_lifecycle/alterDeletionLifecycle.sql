ALTER TABLE user
  ADD COLUMN deletion_lifecycle_state VARCHAR(32) NULL,
  ADD COLUMN deletion_read_only_until DATETIME NULL,
  ADD COLUMN deletion_paused_until DATETIME NULL,
  ADD COLUMN deletion_pause_reason VARCHAR(512) NULL,
  ADD COLUMN deletion_paused_by VARCHAR(64) NULL,
  ADD COLUMN deletion_pause_created_at DATETIME NULL;

ALTER TABLE consultant
  ADD COLUMN deletion_lifecycle_state VARCHAR(32) NULL,
  ADD COLUMN deletion_read_only_until DATETIME NULL,
  ADD COLUMN deletion_paused_until DATETIME NULL,
  ADD COLUMN deletion_pause_reason VARCHAR(512) NULL,
  ADD COLUMN deletion_paused_by VARCHAR(64) NULL,
  ADD COLUMN deletion_pause_created_at DATETIME NULL;

UPDATE user
SET deletion_lifecycle_state = CASE
  WHEN delete_date IS NULL THEN 'ACTIVE'
  ELSE 'READ_ONLY_SAFEGUARD'
END
WHERE deletion_lifecycle_state IS NULL;

UPDATE consultant
SET deletion_lifecycle_state = CASE
  WHEN delete_date IS NULL THEN 'ACTIVE'
  ELSE 'READ_ONLY_SAFEGUARD'
END
WHERE deletion_lifecycle_state IS NULL;

CREATE TABLE identity_tombstone (
  id BIGINT NOT NULL AUTO_INCREMENT,
  subject_id VARCHAR(64) NOT NULL,
  subject_type VARCHAR(16) NOT NULL,
  display_label VARCHAR(255) NOT NULL,
  hard_deleted_at DATETIME NOT NULL,
  source_delete_date DATETIME NULL,
  tenant_id BIGINT NULL,
  PRIMARY KEY (id),
  INDEX idx_identity_tombstone_subject_id (subject_id),
  INDEX idx_identity_tombstone_subject_type (subject_type),
  INDEX idx_identity_tombstone_deleted_at (hard_deleted_at)
);
