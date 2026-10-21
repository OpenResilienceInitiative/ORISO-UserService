-- Nondispatchable PREPARED metadata precedes bounded reads.
-- Domain mutation plus FROZEN/applied marker commit in the same owning User transaction.
-- This locale-only intent stores no human JWT, password, OTP secret, code or proof.
CREATE TABLE identity_account_operation_intent (
  operation_id VARCHAR(36) NOT NULL,
  realm_id VARCHAR(255) NOT NULL,
  realm_name VARCHAR(255) NOT NULL,
  account_id VARCHAR(36) NOT NULL,
  tenant_id BIGINT NULL,
  owner_client VARCHAR(255) NOT NULL,
  owner_subject VARCHAR(36) NOT NULL,
  request_digest VARCHAR(64) NOT NULL,
  target_json LONGTEXT NOT NULL,
  intent_body LONGTEXT NULL,
  status VARCHAR(24) NOT NULL,
  domain_applied BOOLEAN NOT NULL,
  receipt LONGTEXT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (operation_id)
);
