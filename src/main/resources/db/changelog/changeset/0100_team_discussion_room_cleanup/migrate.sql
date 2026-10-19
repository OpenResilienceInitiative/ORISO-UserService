CREATE TABLE IF NOT EXISTS team_discussion_room_cleanup_task (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  matrix_room_id VARCHAR(255) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  last_attempt_at DATETIME(6) NULL,
  create_date DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_team_discussion_room_cleanup_room (matrix_room_id),
  KEY idx_team_discussion_room_cleanup_created (create_date)
);
