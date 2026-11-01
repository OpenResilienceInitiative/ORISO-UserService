ALTER TABLE session
  DROP CONSTRAINT chk_session_ownership_revision_nonnegative,
  DROP COLUMN row_version,
  DROP COLUMN ownership_revision;
