-- The existing reactivation saga owns this nullable raw capsule.
-- User's mapped full29 projection remains unchanged; chosen passwords are AEAD only.
ALTER TABLE user ADD COLUMN IF NOT EXISTS reactivation_scoped_json LONGTEXT NULL;
