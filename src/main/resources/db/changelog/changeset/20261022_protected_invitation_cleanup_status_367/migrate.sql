CREATE TABLE protected_invitation_cleanup_status (
  invite_id BIGINT NOT NULL PRIMARY KEY,
  original_ack_json LONGTEXT NULL,
  recovery_json LONGTEXT NULL,
  local_outcome_json LONGTEXT NULL,
  final_status_json LONGTEXT NULL,
  expected_generation BIGINT NULL,
  recovery_id VARCHAR(36) NULL,
  last_attempt_at TIMESTAMP(6) NOT NULL,
  completed_at TIMESTAMP(6) NULL,
  KEY ix_protected_cleanup_status_pending (completed_at,last_attempt_at,invite_id),
  CONSTRAINT fk_protected_cleanup_status_invite FOREIGN KEY (invite_id) REFERENCES account_invite(id)
);
