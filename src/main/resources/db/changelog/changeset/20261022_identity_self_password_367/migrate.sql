CREATE TABLE identity_self_password_intent (
  operation_id VARCHAR(36) NOT NULL,
  realm_id VARCHAR(512) NOT NULL,
  account_id VARCHAR(36) NOT NULL,
  tenant_id BIGINT NULL,
  owner_client VARCHAR(255) NOT NULL,
  owner_subject VARCHAR(36) NOT NULL,
  request_digest CHAR(64) NOT NULL,
  target_json LONGTEXT NOT NULL,
  authority_valid_until BIGINT NOT NULL,
  metadata_json LONGTEXT NULL,
  intent_envelope LONGTEXT NULL,
  status VARCHAR(32) NOT NULL,
  receipt LONGTEXT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (operation_id),
  INDEX idx_identity_self_password_account (account_id, status)
);
