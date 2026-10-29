-- Separate the existing SELF and configured-import ownership namespaces in the same journal.
ALTER TABLE identity_account_operation_intent ADD COLUMN operation_kind VARCHAR(32) NOT NULL DEFAULT 'SELF_LOCALE';
ALTER TABLE identity_account_operation_intent ADD COLUMN source_key VARCHAR(64) NULL;
ALTER TABLE identity_account_operation_intent ADD COLUMN source_json LONGTEXT NULL;
ALTER TABLE identity_account_operation_intent ADD COLUMN applied_json LONGTEXT NULL;
ALTER TABLE identity_account_operation_intent ADD CONSTRAINT uq_identity_operation_source UNIQUE (operation_kind, source_key);
