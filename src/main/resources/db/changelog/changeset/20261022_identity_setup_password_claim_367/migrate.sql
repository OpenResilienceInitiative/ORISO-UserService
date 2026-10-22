CREATE TABLE identity_setup_password_claim (
  invite_id BIGINT NOT NULL PRIMARY KEY,
  claim_id VARCHAR(36) NOT NULL UNIQUE,
  claim_version BIGINT NOT NULL,
  account_id VARCHAR(36) NOT NULL,
  token_hash VARCHAR(64) NOT NULL,
  authority_valid_until TIMESTAMP NOT NULL,
  request_digest VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  updated_at TIMESTAMP NOT NULL
);
