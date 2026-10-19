CREATE TABLE consultant_picture (
  consultant_id VARCHAR(36) COLLATE utf8_unicode_ci NOT NULL,
  image_bytes MEDIUMBLOB NOT NULL,
  content_type VARCHAR(10) NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (consultant_id),
  CONSTRAINT fk_consultant_picture_owner FOREIGN KEY (consultant_id)
    REFERENCES consultant (consultant_id) ON DELETE CASCADE,
  CONSTRAINT ck_consultant_picture_size CHECK (OCTET_LENGTH(image_bytes) BETWEEN 1 AND 5242880),
  CONSTRAINT ck_consultant_picture_type CHECK (content_type IN ('image/png', 'image/jpeg'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
