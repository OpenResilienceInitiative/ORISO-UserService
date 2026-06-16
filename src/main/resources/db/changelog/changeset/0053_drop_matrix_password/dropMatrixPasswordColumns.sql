UPDATE user SET matrix_password = NULL WHERE matrix_password IS NOT NULL;
UPDATE consultant SET matrix_password = NULL WHERE matrix_password IS NOT NULL;
ALTER TABLE user DROP COLUMN matrix_password;
ALTER TABLE consultant DROP COLUMN matrix_password;
