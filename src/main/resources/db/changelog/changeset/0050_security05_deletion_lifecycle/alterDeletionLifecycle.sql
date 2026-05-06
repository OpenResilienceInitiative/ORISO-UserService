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

CREATE TABLE agency_invite_link (
  id BIGINT NOT NULL AUTO_INCREMENT,
  token VARCHAR(64) NOT NULL,
  tenant_id BIGINT NOT NULL,
  agency_id BIGINT NOT NULL,
  consulting_type_id INT NULL,
  created_by_user_id VARCHAR(36) NOT NULL,
  created_by_username VARCHAR(255) NULL,
  create_date DATETIME NOT NULL,
  expires_at DATETIME NULL,
  used_at DATETIME NULL,
  used_by_session_id BIGINT NULL,
  status VARCHAR(20) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agency_invite_link_token (token),
  INDEX idx_agency_invite_link_tenant_create_date (tenant_id, create_date),
  INDEX idx_agency_invite_link_agency_create_date (agency_id, create_date)
);

CREATE TABLE inactive_account_notification_audit_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  notification_fingerprint VARCHAR(255) NOT NULL,
  account_role VARCHAR(32) NOT NULL,
  account_id VARCHAR(64) NOT NULL,
  account_tenant_id BIGINT NULL,
  last_activity_at DATETIME NULL,
  threshold_days INT NOT NULL,
  recipient_admin_id VARCHAR(64) NOT NULL,
  recipient_email VARCHAR(255) NOT NULL,
  email_dispatched TINYINT NOT NULL DEFAULT 0,
  create_date DATETIME NOT NULL,
  tenant_id BIGINT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_inactive_account_notification_fingerprint (notification_fingerprint),
  INDEX idx_inactive_account_notification_tenant_date (tenant_id, create_date),
  INDEX idx_inactive_account_notification_account (account_role, account_id)
);
