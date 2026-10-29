CREATE TABLE protected_otp_bootstrap (
 invite_id BIGINT NOT NULL PRIMARY KEY,
 claim_id VARCHAR(36) NOT NULL UNIQUE,
 metadata_json LONGTEXT NOT NULL,
 request_envelope LONGTEXT NOT NULL,
 result_envelope LONGTEXT NULL,
 state VARCHAR(32) NOT NULL,
 created_at TIMESTAMP NOT NULL,
 updated_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_protected_otp_bootstrap_invite FOREIGN KEY (invite_id) REFERENCES account_invite(id)
);
