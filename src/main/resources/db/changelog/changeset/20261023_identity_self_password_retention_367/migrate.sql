ALTER TABLE identity_self_password_intent ADD COLUMN intent_digest CHAR(64) NULL;
CREATE INDEX idx_identity_self_password_expiry ON identity_self_password_intent (authority_valid_until, operation_id);
