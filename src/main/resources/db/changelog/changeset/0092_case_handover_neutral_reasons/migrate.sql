-- Neutral reason names are resolved by service aliases. Preserve every configured policy
-- field and every historical request value, including NULL access_type. In particular,
-- do not replay the old 0086 default-duration/expiry backfill on already granted access.
CREATE TABLE IF NOT EXISTS case_handover_reason_policy (
  code VARCHAR(100) NOT NULL,
  label VARCHAR(255) NOT NULL,
  client_consent_required TINYINT(1) NOT NULL DEFAULT 0,
  access_allowed TINYINT(1) NOT NULL DEFAULT 1,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  display_order INT NOT NULL DEFAULT 100,
  policy_authority VARCHAR(255) NOT NULL,
  client_notification_templates JSON NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (code),
  INDEX idx_case_handover_reason_enabled_order (enabled, display_order, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;

ALTER TABLE case_handover_reason_policy
  ADD COLUMN IF NOT EXISTS max_access_duration_minutes INT NULL;

ALTER TABLE case_handover_request
  ADD COLUMN IF NOT EXISTS access_type VARCHAR(20) NULL,
  ADD COLUMN IF NOT EXISTS max_access_duration_minutes INT NULL,
  ADD COLUMN IF NOT EXISTS expires_at DATETIME NULL;

CREATE INDEX IF NOT EXISTS idx_case_handover_co_access_expiry
  ON case_handover_request (status, access_type, expires_at);

-- Identical table contract to the existing 0087 cache. Existing rows and recorded
-- changeset IDs/checksums are untouched; a fresh deployment gets the missing table.
CREATE TABLE IF NOT EXISTS tenant_case_handover_policy_cache (
  tenant_id BIGINT NOT NULL,
  policies LONGTEXT NOT NULL,
  refreshed_at DATETIME NOT NULL,
  stale_since DATETIME NULL,
  PRIMARY KEY (tenant_id)
);
