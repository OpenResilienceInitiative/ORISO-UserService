-- #1526: product tours become an opt-in switch under Profile -> Help, off by default.
-- There are no production users yet, so every existing counsellor starts from off once.
-- Liquibase runs this changeset a single time; later choices by counsellors are kept.
ALTER TABLE consultant ALTER COLUMN walk_through_enabled SET DEFAULT 0;
UPDATE consultant SET walk_through_enabled = 0 WHERE walk_through_enabled <> 0;
