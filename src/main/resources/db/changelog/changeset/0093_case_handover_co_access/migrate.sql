ALTER TABLE case_handover_reason_policy
  ADD COLUMN IF NOT EXISTS max_access_duration_minutes INT NULL;

UPDATE case_handover_reason_policy
SET max_access_duration_minutes = 180
WHERE code = 'COUNSELLOR_ASKED_FOR_ADVICE'
  AND max_access_duration_minutes IS NULL;

ALTER TABLE case_handover_request
  ADD COLUMN IF NOT EXISTS access_type VARCHAR(20) NULL,
  ADD COLUMN IF NOT EXISTS max_access_duration_minutes INT NULL,
  ADD COLUMN IF NOT EXISTS expires_at DATETIME NULL;

UPDATE case_handover_request
SET access_type = CASE
      WHEN reason_code = 'COUNSELLOR_ASKED_FOR_ADVICE' THEN 'CO_ACCESS'
      ELSE 'TAKEOVER'
    END,
    max_access_duration_minutes = CASE
      WHEN reason_code = 'COUNSELLOR_ASKED_FOR_ADVICE' THEN 180
      ELSE NULL
    END,
    expires_at = CASE
      WHEN reason_code = 'COUNSELLOR_ASKED_FOR_ADVICE' AND status = 'GRANTED'
        THEN DATE_ADD(COALESCE(resolved_at, created_at), INTERVAL 180 MINUTE)
      ELSE NULL
    END
WHERE access_type IS NULL;

CREATE INDEX IF NOT EXISTS idx_case_handover_co_access_expiry
  ON case_handover_request (status, access_type, expires_at);
