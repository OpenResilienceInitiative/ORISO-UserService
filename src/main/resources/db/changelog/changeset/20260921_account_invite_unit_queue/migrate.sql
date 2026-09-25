-- Invites into a not-yet-created Beratungsstelle / Träger wait (status WAITING_FOR_UNIT, fits the
-- existing VARCHAR(32) status column) and are sent when the unit's first admin finishes
-- onboarding. The expiry clock starts at that send.
ALTER TABLE account_invite
  ADD COLUMN IF NOT EXISTS waiting_for_unit VARCHAR(16) NULL,
  ADD COLUMN IF NOT EXISTS queued_template_id BIGINT NULL,
  ADD COLUMN IF NOT EXISTS queued_expiry_days BIGINT NULL,
  ADD COLUMN IF NOT EXISTS import_batch_id VARCHAR(64) NULL;
CREATE INDEX IF NOT EXISTS idx_account_invite_waiting_unit
  ON account_invite (status, waiting_for_unit, agency_id, tenant_id);
