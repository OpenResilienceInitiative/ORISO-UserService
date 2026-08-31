-- Restore the canonical 0079 table when a divergent ADR-018 hot-deploy
-- removed it while leaving DATABASECHANGELOG history intact. Idempotent for
-- normal databases where 0079's table and indexes still exist.
CREATE TABLE IF NOT EXISTS userservice.support_room (
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

CREATE INDEX IF NOT EXISTS idx_support_room_status_expiry
  ON userservice.support_room (status, expiry_date);
CREATE INDEX IF NOT EXISTS idx_support_room_consultant
  ON userservice.support_room (consultant_id, status);
CREATE INDEX IF NOT EXISTS idx_support_room_support_admin
  ON userservice.support_room (support_admin_id, status);
