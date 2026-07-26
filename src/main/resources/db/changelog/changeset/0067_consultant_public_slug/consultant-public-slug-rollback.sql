DROP TABLE IF EXISTS reserved_public_slug;

DROP INDEX ux_consultant_pending_public_slug ON consultant;
DROP INDEX ux_consultant_public_slug ON consultant;

ALTER TABLE consultant
  DROP COLUMN public_slug_reviewed_at,
  DROP COLUMN public_slug_status,
  DROP COLUMN pending_public_slug,
  DROP COLUMN public_slug;
