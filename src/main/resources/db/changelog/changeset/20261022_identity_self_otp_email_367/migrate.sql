-- Pending address stays only in the encrypted frozen intent. This is an owned UUID pointer.
CREATE TABLE identity_self_otp_pending_email (
  account_id VARCHAR(36) NOT NULL,
  start_operation_id VARCHAR(36) NOT NULL,
  PRIMARY KEY (account_id),
  UNIQUE (start_operation_id)
);
