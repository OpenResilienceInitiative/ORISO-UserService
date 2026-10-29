CREATE TABLE protected_invitation_mail_outbox (
  invite_id BIGINT NOT NULL,
  approval_id VARCHAR(36) NOT NULL,
  metadata_json LONGTEXT NOT NULL,
  sealed_mail LONGTEXT NOT NULL,
  state VARCHAR(32) NOT NULL,
  sent_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (invite_id),
  CONSTRAINT fk_protected_mail_invite FOREIGN KEY (invite_id) REFERENCES account_invite(id),
  CONSTRAINT fk_protected_mail_approval FOREIGN KEY (approval_id) REFERENCES protected_invitation_authorization(approval_id)
);
