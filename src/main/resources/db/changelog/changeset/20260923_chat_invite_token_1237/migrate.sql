-- Secret part of a self-help group's invite link (#1237). The group number in the link is
-- guessable; joining by number now also needs this token. Existing rows stay NULL and get a
-- token from the application (SecureRandom) the first time a counsellor opens the group, so no
-- predictable value is ever written by SQL.
ALTER TABLE chat
  ADD COLUMN IF NOT EXISTS invite_token VARCHAR(64) NULL DEFAULT NULL;
