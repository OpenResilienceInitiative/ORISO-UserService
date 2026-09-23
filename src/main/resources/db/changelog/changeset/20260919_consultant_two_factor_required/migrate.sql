-- Consultants whose login was provisioned by an admin must establish a second
-- factor before the account is used. Existing rows default to 0: the rule is for
-- accounts created from here on, not a retroactive lockout of people at work.
ALTER TABLE consultant
  ADD COLUMN IF NOT EXISTS two_factor_required TINYINT(1) NOT NULL DEFAULT 0;
