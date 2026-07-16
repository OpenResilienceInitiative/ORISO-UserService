ALTER TABLE consultant
  ADD COLUMN public_slug VARCHAR(128) NULL,
  ADD COLUMN pending_public_slug VARCHAR(128) NULL,
  ADD COLUMN public_slug_status VARCHAR(32) NULL,
  ADD COLUMN public_slug_reviewed_at DATETIME NULL;

CREATE UNIQUE INDEX ux_consultant_public_slug ON consultant(public_slug);
CREATE UNIQUE INDEX ux_consultant_pending_public_slug ON consultant(pending_public_slug);

CREATE TABLE reserved_public_slug (
  id BIGINT NOT NULL AUTO_INCREMENT,
  slug VARCHAR(128) NOT NULL,
  reason VARCHAR(512) NULL,
  active TINYINT NOT NULL DEFAULT 1,
  created_by VARCHAR(64) NULL,
  created_at DATETIME NULL,
  updated_by VARCHAR(64) NULL,
  updated_at DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_reserved_public_slug (slug)
);

INSERT INTO reserved_public_slug (slug, reason, active, created_by, created_at)
VALUES
  ('admin', 'Reserved system route', 1, 'system', UTC_TIMESTAMP()),
  ('api', 'Reserved system route', 1, 'system', UTC_TIMESTAMP()),
  ('auth', 'Reserved system route', 1, 'system', UTC_TIMESTAMP()),
  ('login', 'Reserved system route', 1, 'system', UTC_TIMESTAMP()),
  ('registration', 'Reserved system route', 1, 'system', UTC_TIMESTAMP()),
  ('support', 'Reserved support route', 1, 'system', UTC_TIMESTAMP()),
  ('system', 'Reserved system route', 1, 'system', UTC_TIMESTAMP());
