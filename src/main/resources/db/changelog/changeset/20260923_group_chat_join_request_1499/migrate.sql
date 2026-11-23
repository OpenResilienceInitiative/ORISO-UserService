-- Knock-to-join for self-help groups (ORISO-Frontend#1499): a counsellor holding the invite
-- link asks to join a Series; an Owner or Co-Moderator admits or declines. Only an ADMITTED
-- request creates a group_chat_participant row; the request itself grants no access.
CREATE TABLE IF NOT EXISTS group_chat_join_request (
  id BIGINT NOT NULL AUTO_INCREMENT,
  series_id BIGINT UNSIGNED NOT NULL,
  consultant_id VARCHAR(36) COLLATE utf8_unicode_ci NOT NULL,
  status VARCHAR(16) NOT NULL,
  via VARCHAR(32) NOT NULL DEFAULT 'INVITE_LINK',
  admitted_role VARCHAR(16) NULL,
  requested_at DATETIME NOT NULL,
  decided_at DATETIME NULL,
  decided_by VARCHAR(36) COLLATE utf8_unicode_ci NULL,
  PRIMARY KEY (id),
  KEY idx_gcjr_series_status (series_id, status),
  KEY idx_gcjr_consultant_series (consultant_id, series_id),
  CONSTRAINT fk_gcjr_series FOREIGN KEY (series_id) REFERENCES chat (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
