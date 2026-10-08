ALTER TABLE account_invite
    ADD COLUMN purpose VARCHAR(32) NOT NULL DEFAULT 'INVITE';

ALTER TABLE account_invite
    ADD COLUMN setup_bound_username VARCHAR(255) NULL;

ALTER TABLE account_invite
    ADD COLUMN active_setup_identity_key VARCHAR(36) NULL;

ALTER TABLE account_invite
    ADD COLUMN initial_password_verifier VARCHAR(255) NULL;

CREATE UNIQUE INDEX idx_account_invite_active_setup_identity
    ON account_invite(active_setup_identity_key);
