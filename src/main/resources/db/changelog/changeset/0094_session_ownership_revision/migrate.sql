ALTER TABLE session
  ADD COLUMN ownership_revision BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0,
  ADD CONSTRAINT chk_session_ownership_revision_nonnegative
    CHECK (ownership_revision >= 0);
