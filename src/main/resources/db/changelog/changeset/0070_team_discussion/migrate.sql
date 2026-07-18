-- US#473 / ADR-016: Team-Besprechung — team-only discussion room per open enquiry.
-- Idempotent DDL following the 0056/0067 pattern.

CREATE TABLE IF NOT EXISTS team_discussion (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  matrix_room_id VARCHAR(255) NOT NULL,
  discussion_status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  create_date DATETIME NOT NULL,
  archive_date DATETIME NULL,
  created_by_consultant_id VARCHAR(36) NULL,
  is_first_notified TINYINT(1) NOT NULL DEFAULT 0,
  tenant_id BIGINT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_team_discussion_session (session_id),
  KEY idx_team_discussion_room (matrix_room_id)
);

CREATE TABLE IF NOT EXISTS team_discussion_participant (
  id BIGINT NOT NULL AUTO_INCREMENT,
  team_discussion_id BIGINT NOT NULL,
  consultant_id VARCHAR(36) NOT NULL,
  join_date DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_td_participant (team_discussion_id, consultant_id)
);

-- Server-side mirror of the per-conversation notification level (All / Mentions / Muted
-- + timed snooze). Written by the frontend 4-state menu so the fan-out producer can
-- honour Muted/Snoozed server-side (the Matrix-account-data copy is unreadable here).
CREATE TABLE IF NOT EXISTS notification_room_level (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id VARCHAR(64) NOT NULL,
  room_id VARCHAR(255) NOT NULL,
  notification_level VARCHAR(20) NOT NULL DEFAULT 'ALL',
  snoozed_until DATETIME NULL,
  update_date DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_notification_room_level (user_id, room_id)
);
