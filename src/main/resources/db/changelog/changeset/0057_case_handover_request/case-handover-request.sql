CREATE TABLE IF NOT EXISTS case_handover_request (
  id BIGINT(21) UNSIGNED NOT NULL AUTO_INCREMENT,
  session_id BIGINT(21) UNSIGNED NOT NULL,
  requester_consultant_id VARCHAR(36) NOT NULL,
  previous_consultant_id VARCHAR(36) NULL,
  reason_code VARCHAR(100) NOT NULL,
  reason_label VARCHAR(255) NOT NULL,
  explanation TEXT NOT NULL,
  status VARCHAR(40) NOT NULL,
  client_consent_required TINYINT(1) NOT NULL DEFAULT 0,
  policy_authority VARCHAR(255) NOT NULL,
  audit_outcome VARCHAR(100) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved_at DATETIME NULL,
  tenant_id BIGINT NULL,
  PRIMARY KEY (id),
  INDEX idx_case_handover_session_requester_created (
    session_id,
    requester_consultant_id,
    created_at
  ),
  INDEX idx_case_handover_tenant_created (tenant_id, created_at),
  INDEX idx_case_handover_status (status),
  CONSTRAINT case_handover_request_session_fk
    FOREIGN KEY (session_id) REFERENCES session (id) ON UPDATE CASCADE,
  CONSTRAINT case_handover_request_requester_fk
    FOREIGN KEY (requester_consultant_id) REFERENCES consultant (consultant_id) ON UPDATE CASCADE,
  CONSTRAINT case_handover_request_previous_fk
    FOREIGN KEY (previous_consultant_id) REFERENCES consultant (consultant_id) ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;

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
