-- New rows claim their normalized recipient in this nullable column. Existing duplicate rows stay
-- NULL, so the migration is non-destructive; the existing service guard still covers them.
ALTER TABLE account_invite
  ADD COLUMN IF NOT EXISTS active_recipient_key VARCHAR(255) NULL AFTER recipient_email;

CREATE UNIQUE INDEX IF NOT EXISTS idx_account_invite_active_recipient
  ON account_invite (active_recipient_key);
