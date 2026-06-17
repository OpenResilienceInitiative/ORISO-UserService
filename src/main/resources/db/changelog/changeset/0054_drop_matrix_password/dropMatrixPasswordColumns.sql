SET @user_matrix_password_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'user'
    AND COLUMN_NAME = 'matrix_password'
);
SET @user_matrix_password_backup_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'user'
    AND COLUMN_NAME = 'matrix_password_backup'
);
SET @sql = IF(
  @user_matrix_password_exists > 0 AND @user_matrix_password_backup_exists = 0,
  'ALTER TABLE `user` CHANGE `matrix_password` `matrix_password_backup` VARCHAR(255) NULL',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @consultant_matrix_password_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'consultant'
    AND COLUMN_NAME = 'matrix_password'
);
SET @consultant_matrix_password_backup_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'consultant'
    AND COLUMN_NAME = 'matrix_password_backup'
);
SET @sql = IF(
  @consultant_matrix_password_exists > 0 AND @consultant_matrix_password_backup_exists = 0,
  'ALTER TABLE `consultant` CHANGE `matrix_password` `matrix_password_backup` VARCHAR(255) NULL',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
