CREATE TABLE IF NOT EXISTS event_notification (
  id BIGINT NOT NULL AUTO_INCREMENT,
  recipient_user_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  category VARCHAR(20) NOT NULL,
  title VARCHAR(255) NOT NULL,
  text TEXT NULL,
  action_path VARCHAR(512) NULL,
  source_session_id BIGINT NULL,
  read_date DATETIME NULL,
  create_date DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  tenant_id BIGINT NULL,
  PRIMARY KEY (id),
  INDEX idx_event_notification_recipient_create (recipient_user_id, create_date),
  INDEX idx_event_notification_recipient_read (recipient_user_id, read_date),
  INDEX idx_event_notification_tenant (tenant_id)
);

