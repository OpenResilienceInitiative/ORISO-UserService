-- Repair for the case-handover reason-policy table on environments where the
-- 0057 changeset was MARK_RAN without executing its SQL. 0057 guarded BOTH the
-- request table and the reason-policy table behind a single AND precondition, so
-- on any environment where case_handover_request already existed (it was
-- hand-applied on dev before 0057 ever ran) the precondition failed and the
-- reason-policy CREATE + seed were skipped entirely. The table then stays empty,
-- listReasonPolicies() falls back to the hardcoded defaults for GET, and the
-- first PUT that tries to persist has no seeded rows to update.
--
-- This statement is idempotent and safe on every environment:
--   * CREATE TABLE IF NOT EXISTS      -> no-op where the table already exists.
--   * INSERT ... ON DUPLICATE KEY ... -> only inserts the default rows that are
--     missing; existing rows (including admin edits) are left untouched.

CREATE TABLE IF NOT EXISTS case_handover_reason_policy (
  code VARCHAR(100) NOT NULL,
  label VARCHAR(255) NOT NULL,
  client_consent_required TINYINT(1) NOT NULL DEFAULT 0,
  access_allowed TINYINT(1) NOT NULL DEFAULT 1,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  display_order INT NOT NULL DEFAULT 100,
  policy_authority VARCHAR(255) NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (code),
  INDEX idx_case_handover_reason_enabled_order (enabled, display_order, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;

INSERT INTO case_handover_reason_policy (
  code,
  label,
  client_consent_required,
  access_allowed,
  enabled,
  display_order,
  policy_authority
) VALUES
  (
    'COUNSELLOR_ASKED_FOR_ADVICE',
    'Counsellor asked for advice',
    1,
    1,
    1,
    10,
    'platform-admin-default-case-handover-policy'
  ),
  (
    'COUNSELLOR_ON_HOLIDAY',
    'Counsellor is on holiday',
    0,
    1,
    1,
    20,
    'platform-admin-default-case-handover-policy'
  ),
  (
    'OTHER_EMERGENCY',
    'Other emergency',
    0,
    1,
    1,
    30,
    'platform-admin-default-case-handover-policy'
  ),
  (
    'COUNSELLOR_IS_ILL',
    'Counsellor is ill',
    0,
    1,
    1,
    40,
    'platform-admin-default-case-handover-policy'
  ),
  (
    'COUNSELLOR_LEFT',
    'Counsellor does not work here anymore',
    0,
    1,
    1,
    50,
    'platform-admin-default-case-handover-policy'
  )
ON DUPLICATE KEY UPDATE code = code;
