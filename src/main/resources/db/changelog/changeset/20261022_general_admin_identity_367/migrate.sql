CREATE TABLE general_admin_identity_intent (
  operation_id VARCHAR(36) NOT NULL,
  account_id VARCHAR(36) NOT NULL,
  tenant_id BIGINT NULL,
  human_subject VARCHAR(36) NOT NULL,
  human_issuer VARCHAR(512) NOT NULL,
  human_client VARCHAR(255) NOT NULL,
  request_digest VARCHAR(64) NOT NULL,
  original_json LONGTEXT NOT NULL,
  desired_json LONGTEXT NOT NULL,
  native_body LONGTEXT NULL,
  native_receipt LONGTEXT NULL,
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (operation_id),
  KEY ix_general_admin_pending (account_id, status, operation_id)
);
