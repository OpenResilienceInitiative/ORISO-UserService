-- WP-06 Activity Timeline (Slice 2, ADR-AT-01): event_notification records carry
-- structured params (JSON) so visible strings can be rendered client-side from
-- i18n templates instead of server-stored display text. This first, additive
-- step only ADDS the nullable column; producers start populating it alongside
-- the existing title/text. Dropping title/text follows once the frontend renders
-- purely from params. Idempotent so it is safe whether applied by Liquibase or
-- by hand on environments where Liquibase is disabled.
ALTER TABLE event_notification ADD COLUMN IF NOT EXISTS params TEXT NULL;
