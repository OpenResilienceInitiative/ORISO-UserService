ALTER TABLE inactive_account_notification_audit_log
  ADD COLUMN email_idempotency_key VARCHAR(128) NULL,
  ADD COLUMN email_template VARCHAR(64) NULL,
  ADD COLUMN email_subject VARCHAR(255) NULL,
  ADD COLUMN email_body TEXT NULL,
  ADD COLUMN email_url VARCHAR(2048) NULL,
  ADD COLUMN email_dispatch_started_at DATETIME NULL,
  ADD COLUMN email_dispatch_attempt_count INT NOT NULL DEFAULT 0;
