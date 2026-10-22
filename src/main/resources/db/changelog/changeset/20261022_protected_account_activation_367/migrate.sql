ALTER TABLE protected_invitation_acceptance ADD COLUMN account_id VARCHAR(36) NULL;

CREATE TABLE protected_account_activation (
  invite_id BIGINT NOT NULL PRIMARY KEY,
  claim_id VARCHAR(36) NOT NULL,
  attempt_id VARCHAR(36) NOT NULL,
  execution_id VARCHAR(36) NOT NULL,
  account_id VARCHAR(36) NOT NULL,
  state VARCHAR(32) NOT NULL,
  created_checkpoint TEXT NOT NULL,
  local_effects_json TEXT NOT NULL,
  local_effects_digest VARCHAR(64) NOT NULL,
  local_outbox_id VARCHAR(36) NOT NULL,
  otp_incarnation VARCHAR(36) NULL,
  otp_receipt TEXT NULL,
  ready_effects_digest VARCHAR(64) NULL,
  native_commit_receipt TEXT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uq_protected_activation_claim (claim_id),
  UNIQUE KEY uq_protected_activation_attempt (attempt_id),
  UNIQUE KEY uq_protected_activation_account (account_id),
  CONSTRAINT fk_protected_activation_invite FOREIGN KEY (invite_id) REFERENCES account_invite(id)
);
