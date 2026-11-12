CREATE TABLE consultant_enquiry_opened (
  id BIGINT NOT NULL AUTO_INCREMENT,
  consultant_id VARCHAR(36) COLLATE utf8_unicode_ci NOT NULL,
  session_id BIGINT(21) UNSIGNED NOT NULL,
  opened_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_consultant_enquiry_opened UNIQUE (consultant_id, session_id),
  CONSTRAINT fk_consultant_enquiry_opened_consultant FOREIGN KEY (consultant_id)
    REFERENCES consultant (consultant_id) ON DELETE CASCADE,
  CONSTRAINT fk_consultant_enquiry_opened_session FOREIGN KEY (session_id)
    REFERENCES session (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
CREATE INDEX idx_consultant_enquiry_opened_session ON consultant_enquiry_opened (session_id);
