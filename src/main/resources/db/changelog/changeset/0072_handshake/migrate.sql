-- ADR-018 Live-Handshake primitive: two-person confirmation sessions plus their
-- audit trail (12-month retention enforced by a scheduled purge in the service).
CREATE TABLE userservice.handshake_session (
  id VARCHAR(36) NOT NULL,
  purpose VARCHAR(40) NOT NULL,
  initiator_id VARCHAR(36) NOT NULL,
  counterpart_id VARCHAR(36) NOT NULL,
  status VARCHAR(16) NOT NULL,
  create_date DATETIME NOT NULL,
  expiry_date DATETIME NOT NULL,
  confirmed_date DATETIME NULL,
  tenant_id BIGINT NULL,
  PRIMARY KEY (id)
);

CREATE INDEX idx_handshake_session_counterpart ON userservice.handshake_session (counterpart_id, status, expiry_date);
CREATE INDEX idx_handshake_session_status_expiry ON userservice.handshake_session (status, expiry_date);

CREATE TABLE userservice.handshake_audit_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  handshake_id VARCHAR(36) NOT NULL,
  purpose VARCHAR(40) NOT NULL,
  event VARCHAR(40) NOT NULL,
  actor_id VARCHAR(36) NULL,
  counterpart_id VARCHAR(36) NULL,
  tenant_id BIGINT NULL,
  create_date DATETIME NOT NULL,
  PRIMARY KEY (id)
);

CREATE INDEX idx_handshake_audit_handshake ON userservice.handshake_audit_event (handshake_id);
CREATE INDEX idx_handshake_audit_create_date ON userservice.handshake_audit_event (create_date);
