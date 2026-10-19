-- Marks the built-in system default invite templates (one per kind and language).
--
-- NOT NULL with a default: every existing row is an ordinary template, and a NOT NULL
-- column without a default would fail every integration test on an empty schema
-- (see the UserService NOT-NULL trap). The entity mirrors the default.
ALTER TABLE invite_email_template
  ADD COLUMN IF NOT EXISTS system_default BIT NOT NULL DEFAULT 0;
