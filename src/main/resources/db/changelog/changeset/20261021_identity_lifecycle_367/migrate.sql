CREATE TABLE identity_lifecycle_attempt (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 request_key VARCHAR(64) NOT NULL UNIQUE,
 identity_json TEXT NOT NULL,
 origin_kind VARCHAR(32) NOT NULL,
 workflow_id VARCHAR(255) NOT NULL,
 tenant_id VARCHAR(20) NULL,
 execution_id VARCHAR(36) NOT NULL,
 original_execution_id VARCHAR(36) NOT NULL,
 generation BIGINT NOT NULL,
 account_id VARCHAR(255) NULL UNIQUE,
 creation_proof TEXT NULL,
 recovery_receipt TEXT NULL,
 terminal_receipt TEXT NULL,
 status VARCHAR(40) NOT NULL,
 journal_version BIGINT NOT NULL,
 lease_until TIMESTAMP(6) NULL,
 update_date TIMESTAMP(6) NOT NULL,
 INDEX idx_identity_lifecycle_retry (status, update_date),
 INDEX idx_identity_lifecycle_workflow (origin_kind,workflow_id)
);
CREATE TABLE identity_lifecycle_outbox (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 attempt_id VARCHAR(36) NOT NULL,
 operation VARCHAR(16) NOT NULL,
 body_json TEXT NOT NULL,
 status VARCHAR(16) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 INDEX idx_identity_lifecycle_outbox_retry (status, created_at),
 CONSTRAINT fk_identity_lifecycle_outbox FOREIGN KEY (attempt_id) REFERENCES identity_lifecycle_attempt(id)
);
-- Effects are independently committed while a saga holds its attempt row lock: deliberately no FK.
CREATE TABLE identity_lifecycle_effect (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 attempt_id VARCHAR(36) NOT NULL,
 account_id VARCHAR(255) NOT NULL,
 tenant_id VARCHAR(20) NULL,
 execution_id VARCHAR(36) NOT NULL,
 effect_kind VARCHAR(32) NOT NULL,
 requested_target VARCHAR(512) NOT NULL,
 target_id VARCHAR(512) NULL,
 provenance VARCHAR(48) NULL,
 state VARCHAR(24) NOT NULL,
 INDEX idx_identity_lifecycle_effect_attempt (attempt_id)
);
