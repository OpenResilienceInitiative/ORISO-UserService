CREATE TABLE matrix_call_binding (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  source_room_id VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
  call_id VARCHAR(191) COLLATE utf8mb4_bin NOT NULL,
  media_room_id VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
  caller_matrix_id VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
  session_id BIGINT UNSIGNED NULL,
  chat_id BIGINT UNSIGNED NULL,
  tenant_id BIGINT NOT NULL,
  invited_at BIGINT NOT NULL,
  invite_expires_at BIGINT NOT NULL,
  video BOOLEAN NOT NULL,
  started_at BIGINT NULL,
  ended_at BIGINT NULL,
  media_observed_at BIGINT NULL,
  next_observation_attempt_at BIGINT NULL,
  CONSTRAINT uk_call_binding_source_call UNIQUE (source_room_id, call_id),
  CONSTRAINT uk_call_binding_media_room UNIQUE (media_room_id),
  CONSTRAINT ck_call_binding_source CHECK ((session_id IS NOT NULL AND chat_id IS NULL) OR (session_id IS NULL AND chat_id IS NOT NULL)),
  CONSTRAINT fk_call_binding_session FOREIGN KEY (session_id) REFERENCES session(id) ON DELETE CASCADE,
  CONSTRAINT fk_call_binding_chat FOREIGN KEY (chat_id) REFERENCES chat(id) ON DELETE CASCADE
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE matrix_call_invitee (
  binding_id BIGINT NOT NULL,
  matrix_user_id VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (binding_id, matrix_user_id),
  CONSTRAINT fk_call_invitee_binding FOREIGN KEY (binding_id) REFERENCES matrix_call_binding(id) ON DELETE CASCADE
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE matrix_call_device (
  binding_id BIGINT NOT NULL,
  state_key VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
  sender_matrix_id VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
  event_timestamp BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  attended BOOLEAN NOT NULL,
  event_id VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (binding_id, state_key),
  CONSTRAINT fk_call_device_binding FOREIGN KEY (binding_id) REFERENCES matrix_call_binding(id) ON DELETE CASCADE
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
