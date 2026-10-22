CREATE TABLE protected_invitation_authorization (
  approval_id VARCHAR(36) NOT NULL,
  invite_id BIGINT NOT NULL,
  workflow_id VARCHAR(36) NOT NULL,
  authorization_revision BIGINT NOT NULL,
  request_json LONGTEXT NOT NULL,
  issuer_realm VARCHAR(255) NOT NULL,
  issuer_subject VARCHAR(36) NOT NULL,
  request_digest VARCHAR(64) NOT NULL,
  state VARCHAR(32) NOT NULL,
  response_json LONGTEXT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (approval_id),
  UNIQUE KEY idx_protected_invitation_invite (invite_id),
  UNIQUE KEY idx_protected_invitation_revision (workflow_id, authorization_revision),
  CONSTRAINT fk_protected_invitation_invite FOREIGN KEY (invite_id) REFERENCES account_invite(id)
);
