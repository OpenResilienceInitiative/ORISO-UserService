-- Immutable native deletion intent/ACK survives the final local user-row removal.
-- Frozen delete bodies contain metadata only, never passwords, OTPs, bearers or proofs.
CREATE TABLE identity_maintenance_intent (
  operation_id VARCHAR(36) NOT NULL,
  realm_id VARCHAR(36) NOT NULL,
  realm_name VARCHAR(255) NOT NULL,
  subject_type VARCHAR(32) NOT NULL,
  subject_id VARCHAR(36) NOT NULL,
  deletion_started_at DATETIME(6) NOT NULL,
  tenant_id BIGINT NULL,
  claim_token VARCHAR(36) NOT NULL,
  claim_until DATETIME(6) NOT NULL,
  status VARCHAR(32) NOT NULL,
  expected_state VARCHAR(64) NULL,
  frozen_body LONGTEXT NULL,
  receipt_json LONGTEXT NULL,
  owner_client VARCHAR(255) NOT NULL,
  owner_subject VARCHAR(36) NOT NULL,
  PRIMARY KEY (operation_id),
  CONSTRAINT uq_identity_maintenance_deletion UNIQUE (subject_type, subject_id, deletion_started_at)
);
