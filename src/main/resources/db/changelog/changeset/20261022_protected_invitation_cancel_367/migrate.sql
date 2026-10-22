CREATE TABLE protected_invitation_cancellation (
  invite_id BIGINT NOT NULL,
  approval_id VARCHAR(36) NOT NULL,
  cancellation_id VARCHAR(36) NOT NULL,
  reason VARCHAR(32) NOT NULL,
  known_scope_digest VARCHAR(64) NULL,
  body_json LONGTEXT NOT NULL,
  state VARCHAR(32) NOT NULL,
  response_json LONGTEXT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (invite_id),
  UNIQUE KEY uq_protected_cancel_id (cancellation_id),
  KEY ix_protected_cancel_pending (state, updated_at, invite_id),
  CONSTRAINT fk_protected_cancel_invite FOREIGN KEY (invite_id) REFERENCES account_invite(id),
  CONSTRAINT fk_protected_cancel_approval FOREIGN KEY (approval_id) REFERENCES protected_invitation_authorization(approval_id)
);
