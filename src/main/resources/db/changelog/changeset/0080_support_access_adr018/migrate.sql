-- ADR-018 Global Support Access.
--
-- Supersedes the first-cut schema from changesets 0078/0079 (PR #502). Those tables are dropped
-- rather than migrated: the feature has never been usable in any environment, because the
-- `global-support-admin` realm role does not exist in any running Keycloak, so no Global Support
-- Admin account — and therefore no handshake and no support room — can ever have been created.
-- Dropping is the honest option; a column-by-column migration would imply data that cannot exist.
DROP TABLE IF EXISTS userservice.support_room;
DROP TABLE IF EXISTS userservice.handshake_audit_event;
DROP TABLE IF EXISTS userservice.handshake_session;

-- `SUPPORT` is seven characters and admin.type was still VARCHAR(6) after 0078/0079 shipped
-- AdminType.SUPPORT — creating a Global Support Admin would truncate or fail.
ALTER TABLE userservice.admin MODIFY COLUMN type VARCHAR(32) NOT NULL;

-- Operational state of a Global Support Admin. This row, not the bearer token, decides whether a
-- GSA may act, so a token issued before a disable stops working immediately.
CREATE TABLE userservice.support_admin_profile (
  admin_id VARCHAR(36) NOT NULL,
  status VARCHAR(24) NOT NULL,
  provisioning_attempts INT NOT NULL DEFAULT 0,
  last_error VARCHAR(1000) NULL,
  create_date DATETIME NOT NULL,
  update_date DATETIME NOT NULL,
  disabled_date DATETIME NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (admin_id)
);

CREATE INDEX idx_support_admin_profile_status ON userservice.support_admin_profile (status);

-- Live-handshake primitive. A lapsed handshake leaves no row here at all, only one audit entry.
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
  agency_id BIGINT NULL,
  confirm_attempts INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);

CREATE INDEX idx_handshake_session_counterpart ON userservice.handshake_session (counterpart_id, status, expiry_date);
CREATE INDEX idx_handshake_session_status_expiry ON userservice.handshake_session (status, expiry_date);
CREATE INDEX idx_handshake_session_scope ON userservice.handshake_session (initiator_id, counterpart_id, agency_id, status);

CREATE TABLE userservice.handshake_audit_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  handshake_id VARCHAR(36) NOT NULL,
  purpose VARCHAR(40) NOT NULL,
  event VARCHAR(40) NOT NULL,
  actor_id VARCHAR(36) NULL,
  counterpart_id VARCHAR(36) NULL,
  tenant_id BIGINT NULL,
  agency_id BIGINT NULL,
  create_date DATETIME NOT NULL,
  PRIMARY KEY (id)
);

CREATE INDEX idx_handshake_audit_handshake ON userservice.handshake_audit_event (handshake_id);
CREATE INDEX idx_handshake_audit_create_date ON userservice.handshake_audit_event (create_date);
CREATE INDEX idx_handshake_audit_scope ON userservice.handshake_audit_event (tenant_id, agency_id, create_date);

CREATE TABLE userservice.handshake_outbox_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  aggregate_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  create_date DATETIME NOT NULL,
  next_attempt_date DATETIME NOT NULL,
  processed_date DATETIME NULL,
  last_error VARCHAR(1000) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_handshake_outbox_aggregate_event UNIQUE (aggregate_id, event_type)
);

CREATE INDEX idx_handshake_outbox_pending
  ON userservice.handshake_outbox_event (status, next_attempt_date, id);

-- Temporary Support Access: fresh encrypted 1:1 room per confirmed handshake, hard four-hour
-- lease, never reused.
CREATE TABLE userservice.support_access_session (
  id VARCHAR(36) NOT NULL,
  handshake_id VARCHAR(36) NOT NULL,
  matrix_room_id VARCHAR(255) NULL,
  call_matrix_room_id VARCHAR(255) NULL,
  support_admin_id VARCHAR(36) NOT NULL,
  support_admin_matrix_id VARCHAR(255) NULL,
  consultant_id VARCHAR(36) NOT NULL,
  agency_id BIGINT NULL,
  status VARCHAR(24) NOT NULL,
  -- Set while the session is non-terminal, NULL once it is terminal. Many NULLs are allowed by a
  -- unique index, so this permits any number of closed sessions per pair but only one running one.
  active_lease_key VARCHAR(128) NULL,
  close_reason VARCHAR(32) NULL,
  provisioning_attempts INT NOT NULL DEFAULT 0,
  last_error VARCHAR(1000) NULL,
  create_date DATETIME NOT NULL,
  expiry_date DATETIME NOT NULL,
  revocation_started_date DATETIME NULL,
  closed_date DATETIME NULL,
  tenant_id BIGINT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_support_session_handshake UNIQUE (handshake_id),
  CONSTRAINT uk_support_session_active_lease UNIQUE (active_lease_key)
);

CREATE INDEX idx_support_session_status_expiry ON userservice.support_access_session (status, expiry_date);
CREATE INDEX idx_support_session_consultant ON userservice.support_access_session (consultant_id, status);
CREATE INDEX idx_support_session_support_admin ON userservice.support_access_session (support_admin_id, status);
