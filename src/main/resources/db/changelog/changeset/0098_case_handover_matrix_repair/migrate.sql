CREATE TABLE IF NOT EXISTS case_handover_matrix_repair_task (
  id BIGINT NOT NULL AUTO_INCREMENT,
  action VARCHAR(16) NOT NULL,
  room_id VARCHAR(255) NOT NULL,
  member_id VARCHAR(255) NOT NULL,
  session_id BIGINT NOT NULL,
  requester_consultant_id VARCHAR(36) NOT NULL,
  operator_id VARCHAR(255) NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  last_attempt_at DATETIME(6) NULL,
  create_date DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY idx_case_handover_matrix_repair_action_room_member (action, room_id, member_id),
  KEY idx_case_handover_matrix_repair_retry (attempt_count, last_attempt_at, create_date)
);
