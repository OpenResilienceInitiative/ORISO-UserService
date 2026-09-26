CREATE TABLE reply_email_delivery (
  id BIGINT NOT NULL AUTO_INCREMENT,
  recipient_user_id VARCHAR(36) NOT NULL,
  event_key VARCHAR(64) NOT NULL,
  tenant_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME NOT NULL,
  sent_at DATETIME NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_reply_email_recipient_event UNIQUE (recipient_user_id, event_key)
);
