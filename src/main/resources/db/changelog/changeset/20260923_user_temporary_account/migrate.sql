-- "Ohne Konto beitreten" (FE#1499): a person who joins a self-help group through its invite link
-- without an account gets a real account whose only login lives in the browser. The flag lets the
-- temporary-account deletion job find these accounts. Existing rows default to 0: every account
-- created so far stays a permanent one.
ALTER TABLE `user`
  ADD COLUMN IF NOT EXISTS temporary_account TINYINT(1) NOT NULL DEFAULT 0;
