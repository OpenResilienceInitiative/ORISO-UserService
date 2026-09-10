-- New rows claim their normalized recipient in this nullable column. Existing duplicate rows stay
-- NULL, so the migration is non-destructive; the existing service guard still covers them.
ALTER TABLE account_invite
  ADD COLUMN active_recipient_key VARCHAR(255) NULL AFTER recipient_email,
  ADD UNIQUE KEY idx_account_invite_active_recipient (active_recipient_key);
