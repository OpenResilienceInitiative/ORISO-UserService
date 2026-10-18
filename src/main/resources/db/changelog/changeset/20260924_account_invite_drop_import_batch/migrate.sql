-- The CSV batch no longer lets an invite wait without an admin invite, so nothing reads it.
-- Dropped in its own changeset because Pre-Dev already ran 20260921-account-invite-unit-queue.
ALTER TABLE account_invite
  DROP COLUMN IF EXISTS import_batch_id;
