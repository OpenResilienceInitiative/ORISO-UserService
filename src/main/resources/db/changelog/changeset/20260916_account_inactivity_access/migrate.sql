-- No account foreign keys: recovery metadata survives deletion and commits independently.
CREATE TABLE account_inactivity_access_state (
 identity_id VARCHAR(36) NOT NULL PRIMARY KEY,
 keycloak_enabled BOOLEAN NOT NULL,
 restored BOOLEAN NOT NULL,
 deletion_authorized BOOLEAN NOT NULL
);
CREATE TABLE account_inactivity_matrix_state (
 identity_id VARCHAR(36) NOT NULL,
 matrix_user_id VARCHAR(255) NOT NULL,
 original_locked BOOLEAN NOT NULL,
 PRIMARY KEY(identity_id,matrix_user_id)
);
