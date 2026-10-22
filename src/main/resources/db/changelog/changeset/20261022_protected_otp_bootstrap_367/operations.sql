CREATE TABLE protected_otp_bootstrap_operation (
 public_intent_id VARCHAR(64) NOT NULL PRIMARY KEY,
 invite_id BIGINT NOT NULL,
 claim_id VARCHAR(36) NOT NULL,
 operation_id VARCHAR(36) NOT NULL UNIQUE,
 request_digest VARCHAR(64) NOT NULL,
 metadata_json LONGTEXT NOT NULL,
 command_envelope LONGTEXT NOT NULL,
 result_json LONGTEXT NULL,
 state VARCHAR(32) NOT NULL,
 created_at TIMESTAMP NOT NULL,
 updated_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_protected_otp_operation_invite FOREIGN KEY (invite_id) REFERENCES protected_otp_bootstrap(invite_id)
);
