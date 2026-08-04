-- ADR-018 Temporary Support Access: fresh encrypted 1:1 room per confirmed
-- handshake, hard four-hour lease, never reused.
CREATE TABLE userservice.support_room (
  id VARCHAR(36) NOT NULL,
  handshake_id VARCHAR(36) NOT NULL,
  matrix_room_id VARCHAR(255) NOT NULL,
  support_admin_id VARCHAR(36) NOT NULL,
  support_admin_matrix_id VARCHAR(255) NULL,
  consultant_id VARCHAR(36) NOT NULL,
  status VARCHAR(16) NOT NULL,
  close_reason VARCHAR(16) NULL,
  create_date DATETIME NOT NULL,
  expiry_date DATETIME NOT NULL,
  closed_date DATETIME NULL,
  tenant_id BIGINT NULL,
  PRIMARY KEY (id)
);

CREATE INDEX idx_support_room_status_expiry ON userservice.support_room (status, expiry_date);
CREATE INDEX idx_support_room_consultant ON userservice.support_room (consultant_id, status);
CREATE INDEX idx_support_room_support_admin ON userservice.support_room (support_admin_id, status);
