ALTER TABLE account_invite
  DROP INDEX IF EXISTS idx_account_invite_active_recipient;

ALTER TABLE account_invite
  DROP COLUMN IF EXISTS active_recipient_key;
