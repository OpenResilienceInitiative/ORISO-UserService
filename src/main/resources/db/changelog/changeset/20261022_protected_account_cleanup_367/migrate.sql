CREATE TABLE protected_account_cleanup (
  invite_id BIGINT NOT NULL PRIMARY KEY,
  body_json TEXT NOT NULL,
  effects_digest VARCHAR(64) NOT NULL,
  outbox_id VARCHAR(36) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uq_protected_cleanup_outbox (outbox_id),
  CONSTRAINT fk_protected_cleanup_invite FOREIGN KEY (invite_id) REFERENCES account_invite(id)
);
