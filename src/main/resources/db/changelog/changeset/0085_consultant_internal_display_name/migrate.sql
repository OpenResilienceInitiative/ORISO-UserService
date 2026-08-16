-- Guarded so the migration is idempotent (repo convention since changeset 0045).
ALTER TABLE consultant ADD COLUMN IF NOT EXISTS internal_display_name VARCHAR(255) NULL;
