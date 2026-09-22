-- A counsellor provisioned through the admin API starts with a password their
-- administrator chose and passed on, so it is a shared secret until replaced.
-- Existing rows default to 0: the rule is for accounts created from here on.
ALTER TABLE consultant
  ADD COLUMN IF NOT EXISTS password_change_required TINYINT(1) NOT NULL DEFAULT 0;
