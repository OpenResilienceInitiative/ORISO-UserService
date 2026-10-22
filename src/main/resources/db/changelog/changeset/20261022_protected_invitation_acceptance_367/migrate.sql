CREATE TABLE protected_invitation_acceptance (
  invite_id BIGINT NOT NULL,
  approval_id VARCHAR(36) NOT NULL,
  claim_id VARCHAR(36) NOT NULL,
  attempt_id VARCHAR(36) NOT NULL,
  execution_id VARCHAR(36) NOT NULL,
  journal_version BIGINT NOT NULL,
  registration_digest VARCHAR(64) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (invite_id),
  UNIQUE KEY uq_protected_acceptance_claim (claim_id),
  UNIQUE KEY uq_protected_acceptance_attempt (attempt_id),
  CONSTRAINT fk_protected_acceptance_invite FOREIGN KEY (invite_id) REFERENCES account_invite(id),
  CONSTRAINT fk_protected_acceptance_approval FOREIGN KEY (approval_id) REFERENCES protected_invitation_authorization(approval_id)
);
