ALTER TABLE account_invite
  DROP INDEX idx_account_invite_active_recipient,
  DROP COLUMN active_recipient_key;
