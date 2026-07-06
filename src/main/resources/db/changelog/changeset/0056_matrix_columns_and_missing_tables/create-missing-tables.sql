-- Tables that exist on live databases only because Hibernate ddl-auto=update
-- created them — no Liquibase changeset does. DDL matches the JPA entities
-- (DraftMessage, GroupChatParticipant, SessionSupervisor; all use IDENTITY
-- id generation). Idempotent.

CREATE TABLE IF NOT EXISTS draft_message (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id VARCHAR(64) NOT NULL,
  scope_key VARCHAR(255) NOT NULL,
  text TEXT NULL,
  action_path VARCHAR(512) NULL,
  title VARCHAR(255) NULL,
  source_session_id BIGINT NULL,
  room_ref VARCHAR(255) NULL,
  thread_root_id VARCHAR(255) NULL,
  create_date DATETIME NOT NULL,
  update_date DATETIME NOT NULL,
  tenant_id BIGINT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS group_chat_participant (
  id BIGINT NOT NULL AUTO_INCREMENT,
  chat_id BIGINT NOT NULL,
  consultant_id VARCHAR(36) NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS session_supervisor (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  supervisor_consultant_id VARCHAR(255) NOT NULL,
  added_by_consultant_id VARCHAR(255) NOT NULL,
  added_date DATETIME(6) NOT NULL,
  removed_date DATETIME(6) NULL,
  is_active TINYINT NOT NULL DEFAULT 1,
  matrix_room_id VARCHAR(255) NULL,
  notes TEXT NULL,
  PRIMARY KEY (id)
);
