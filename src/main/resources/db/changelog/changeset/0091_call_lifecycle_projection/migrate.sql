CREATE TABLE IF NOT EXISTS call_lifecycle_projection (
  id BIGINT NOT NULL AUTO_INCREMENT,
  matrix_room_id VARCHAR(255) NOT NULL,
  call_room_id VARCHAR(255) NULL,
  call_id VARCHAR(191) NOT NULL,
  call_type VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL,
  actor_user_id VARCHAR(64) NULL,
  source_session_id BIGINT NULL,
  tenant_id BIGINT NULL,
  invited_at DATETIME(3) NULL,
  started_at DATETIME(3) NULL,
  ended_at DATETIME(3) NULL,
  create_date DATETIME(3) NOT NULL,
  update_date DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  INDEX idx_call_lifecycle_call_room_call (call_room_id, call_id),
  CONSTRAINT uk_call_lifecycle_room_call UNIQUE (matrix_room_id, call_id)
);

CREATE TABLE IF NOT EXISTS call_attendance_interval (
  id BIGINT NOT NULL AUTO_INCREMENT,
  call_lifecycle_id BIGINT NOT NULL,
  matrix_user_id VARCHAR(255) NOT NULL,
  domain_user_id VARCHAR(64) NULL,
  device_id VARCHAR(255) NOT NULL,
  joined_at DATETIME(3) NOT NULL,
  left_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  INDEX idx_call_attendance_lifecycle_open (call_lifecycle_id, left_at),
  CONSTRAINT fk_call_attendance_lifecycle FOREIGN KEY (call_lifecycle_id)
    REFERENCES call_lifecycle_projection (id) ON DELETE CASCADE
);
