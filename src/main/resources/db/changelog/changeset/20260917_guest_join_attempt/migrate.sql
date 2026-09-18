-- No cascading invite FK: revoked/deleted invites must not erase retry tombstones.
-- Store only the capability hash, never the raw key, provider passwords or tokens.
CREATE TABLE guest_join_attempt (
  id BIGINT NOT NULL AUTO_INCREMENT,
  key_hash VARCHAR(64) NOT NULL,
  invite_link_id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  topic_id BIGINT NOT NULL,
  consulting_type_id INT NOT NULL,
  identity_user_id VARCHAR(36),
  matrix_user_id VARCHAR(255),
  original_username VARCHAR(30) NOT NULL,
  original_avatar_key VARCHAR(128) NOT NULL,
  language_formal TINYINT NOT NULL,
  session_id BIGINT,
  phase VARCHAR(32) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_guest_join_attempt_key_hash UNIQUE (key_hash),
  CONSTRAINT uk_guest_join_attempt_session UNIQUE (session_id)
);
