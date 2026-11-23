-- ORISO-Admin#1026: dates for the invite tracker. unit_created_at = when the unit a waiting
-- invite needed was created (its release); two_factor_activated_at = the invitee's 2FA
-- activation. Both nullable: existing rows keep NULL ("not known"), nothing is backfilled.
ALTER TABLE account_invite
  ADD COLUMN IF NOT EXISTS unit_created_at DATETIME NULL,
  ADD COLUMN IF NOT EXISTS two_factor_activated_at DATETIME NULL;
