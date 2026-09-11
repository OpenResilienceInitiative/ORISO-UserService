-- Each column is guarded independently so the migration is idempotent and
-- self-healing on a partial schema (MariaDB >= 10.3 syntax, repo convention
-- since changeset 0045).
ALTER TABLE consultant ADD COLUMN IF NOT EXISTS salutation VARCHAR(64) NULL;
ALTER TABLE consultant ADD COLUMN IF NOT EXISTS `position` VARCHAR(255) NULL;
ALTER TABLE consultant ADD COLUMN IF NOT EXISTS title VARCHAR(255) NULL;
ALTER TABLE consultant ADD COLUMN IF NOT EXISTS admin_remarks LONGTEXT NULL;
