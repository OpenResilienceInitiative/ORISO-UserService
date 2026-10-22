CREATE TABLE protected_account_creation (
    invite_id BIGINT NOT NULL PRIMARY KEY,
    approval_id VARCHAR(36) NOT NULL,
    claim_id VARCHAR(36) NOT NULL UNIQUE,
    attempt_id VARCHAR(36) NOT NULL UNIQUE,
    execution_id VARCHAR(36) NOT NULL,
    prepared_checkpoint TEXT NOT NULL,
    creation_fingerprint VARCHAR(64) NOT NULL,
    account_id VARCHAR(36) NULL,
    created_checkpoint TEXT NULL,
    state VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT protected_creation_invite_fk FOREIGN KEY (invite_id) REFERENCES account_invite(id)
);

CREATE TABLE protected_account_recovery (
    invite_id BIGINT NOT NULL,
    expected_generation BIGINT NOT NULL,
    recovery_id VARCHAR(36) NOT NULL UNIQUE,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (invite_id, expected_generation),
    CONSTRAINT protected_recovery_creation_fk FOREIGN KEY (invite_id) REFERENCES protected_account_creation(invite_id)
);
