CREATE TABLE IF NOT EXISTS id_reservation_release_task (
  id BIGINT NOT NULL AUTO_INCREMENT,
  allocation_type VARCHAR(16) NOT NULL,
  reserved_id BIGINT NOT NULL,
  tenant_context_id BIGINT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  last_attempt_at DATETIME(6) NULL,
  create_date DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY idx_id_reservation_release_task_type_id (allocation_type, reserved_id)
);
