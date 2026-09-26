CREATE TABLE reply_email_delivery (
  id BIGINT NOT NULL AUTO_INCREMENT,
  recipient_user_id VARCHAR(36) NOT NULL,
  event_key VARCHAR(64) NOT NULL,
  tenant_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME NOT NULL,
  next_attempt_at DATETIME NOT NULL,
  attempted_at DATETIME NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  sent_at DATETIME NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_reply_email_recipient_event UNIQUE (recipient_user_id, event_key),
  INDEX ix_reply_email_pending (status, next_attempt_at),
  INDEX ix_reply_email_sending (status, attempted_at)
);

CREATE TABLE matrix_email_sync_cursor (
  id BIGINT NOT NULL,
  batch_token TEXT NOT NULL,
  PRIMARY KEY (id)
);
