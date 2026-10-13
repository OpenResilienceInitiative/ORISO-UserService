-- One monotonically increasing revision per occurrence, independent of a moved start time.
CREATE TABLE group_appointment_occurrence_state (
  id BIGINT NOT NULL AUTO_INCREMENT,
  series_id BIGINT UNSIGNED NOT NULL,
  occurrence_index INT NOT NULL,
  revision BIGINT NOT NULL,
  original_start_utc DATETIME NOT NULL,
  effective_start_utc DATETIME NULL,
  timezone VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_gaos_series_occurrence (series_id, occurrence_index),
  CONSTRAINT fk_gaos_series FOREIGN KEY (series_id) REFERENCES chat (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

-- Every recipient and event gets one durable claim. A row left in SENDING after a crash
-- needs operator reconciliation: transport handoff cannot safely be retried blindly.
CREATE TABLE group_appointment_mail_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT,
  series_id BIGINT UNSIGNED NOT NULL,
  occurrence_index INT NOT NULL,
  occurrence_revision BIGINT NOT NULL,
  event_type VARCHAR(16) NOT NULL,
  recipient_role VARCHAR(16) NOT NULL,
  recipient_id VARCHAR(36) COLLATE utf8_unicode_ci NOT NULL,
  correlation_id VARCHAR(36) NOT NULL,
  scheduled_start_utc DATETIME NULL,
  timezone VARCHAR(64) NOT NULL,
  due_at_utc DATETIME NOT NULL,
  next_attempt_at_utc DATETIME NOT NULL,
  failure_count INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME NOT NULL,
  claimed_at DATETIME NULL,
  sent_at DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_gamo_delivery (series_id, occurrence_index, occurrence_revision,
    event_type, recipient_role, recipient_id),
  UNIQUE KEY uq_gamo_correlation (correlation_id),
  KEY idx_gamo_due (status, next_attempt_at_utc, due_at_utc),
  CONSTRAINT fk_gamo_series FOREIGN KEY (series_id) REFERENCES chat (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
