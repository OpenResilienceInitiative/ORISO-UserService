-- PREPARED is metadata only. FROZEN binds the exact encrypted native username/body.
-- No bearer/JWT is stored, including inside the encrypted envelope.
-- Pending outcome and FROZEN commit together; receipt and invite outcome share their later commit.
CREATE TABLE identity_self_app_otp_intent (
  operation_id VARCHAR(36) NOT NULL,
  realm_id VARCHAR(512) NOT NULL,
  realm_name VARCHAR(512) NOT NULL,
  account_id VARCHAR(36) NOT NULL,
  tenant_id BIGINT NULL,
  owner_client VARCHAR(512) NOT NULL,
  owner_subject VARCHAR(36) NOT NULL,
  request_digest VARCHAR(64) NOT NULL,
  target_json LONGTEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  authority_valid_until BIGINT NOT NULL,
  metadata_json LONGTEXT NULL,
  envelope LONGTEXT NULL,
  pending_marker BOOLEAN NOT NULL,
  receipt LONGTEXT NULL,
  native_status INT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (operation_id),
  CONSTRAINT ck_self_app_otp_intent_phase CHECK (
    (status = 'PREPARED' AND pending_marker = FALSE AND metadata_json IS NULL
      AND envelope IS NULL AND receipt IS NULL AND native_status IS NULL)
    OR (status = 'FROZEN' AND pending_marker = TRUE AND metadata_json IS NOT NULL
      AND envelope IS NOT NULL AND receipt IS NULL AND native_status IS NULL)
    OR (status = 'ACKNOWLEDGED' AND pending_marker = TRUE AND metadata_json IS NOT NULL
      AND envelope IS NOT NULL AND receipt IS NOT NULL AND native_status IS NOT NULL
      AND native_status IN (200, 201))
    OR (status = 'TERMINAL_REFUSAL' AND pending_marker = TRUE AND metadata_json IS NOT NULL
      AND envelope IS NOT NULL AND receipt IS NOT NULL AND native_status IS NOT NULL
      AND native_status = 401)
  )
);
