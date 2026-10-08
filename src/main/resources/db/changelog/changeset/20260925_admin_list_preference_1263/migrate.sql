-- An admin's last chosen sort per user-list tab (#1263), so it follows them to every
-- device. A new table: no existing row is touched, so the NOT NULL columns need no
-- default. Keyed by the Keycloak user id, since admins need not own an admin row.
CREATE TABLE IF NOT EXISTS admin_list_preference (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id VARCHAR(64) NOT NULL,
  list_tab VARCHAR(32) NOT NULL,
  sort_field VARCHAR(32) NOT NULL,
  sort_order VARCHAR(4) NOT NULL,
  update_date DATETIME NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_admin_list_preference_user_tab UNIQUE (user_id, list_tab)
);
