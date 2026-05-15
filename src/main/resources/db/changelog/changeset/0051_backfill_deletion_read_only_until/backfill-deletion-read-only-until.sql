-- Backfill deletion_read_only_until for consultants and users that were migrated
-- into READ_ONLY_SAFEGUARD state by changeset 0050 without a proper window set.
-- Without this column the deletion scheduler cannot finalize these accounts,
-- and authenticated-but-soft-deleted consultants/users cause 500 errors on login.

UPDATE consultant
SET deletion_read_only_until = DATE_ADD(NOW(), INTERVAL 48 HOUR)
WHERE delete_date IS NOT NULL
  AND deletion_lifecycle_state = 'READ_ONLY_SAFEGUARD'
  AND deletion_read_only_until IS NULL;

UPDATE user
SET deletion_read_only_until = DATE_ADD(NOW(), INTERVAL 48 HOUR)
WHERE delete_date IS NOT NULL
  AND deletion_lifecycle_state = 'READ_ONLY_SAFEGUARD'
  AND deletion_read_only_until IS NULL;

