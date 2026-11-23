-- Shared H2 integration fixture for the JDBC-owned lifecycle tables.
-- MariaDB dialect and Liquibase replay are verified by the separate required schema job.
CREATE TABLE IF NOT EXISTS account_inactivity_rollout (
 id INT NOT NULL PRIMARY KEY,
 rollout_at DATETIME(6) NOT NULL,
 last_scan DATETIME(6) NULL,
 inventory_complete BOOLEAN NOT NULL DEFAULT FALSE,
 enrolled INT NOT NULL DEFAULT 0,
 missing_new INT NOT NULL DEFAULT 0,
 failed INT NOT NULL DEFAULT 0
);
INSERT IGNORE INTO account_inactivity_rollout(id,rollout_at) VALUES(1,CURRENT_TIMESTAMP(6));
CREATE TABLE IF NOT EXISTS account_inactivity_bootstrap_issue (
 identity_id VARCHAR(36) NOT NULL PRIMARY KEY,
 reason VARCHAR(40) NOT NULL,
 observed_at DATETIME(6) NOT NULL
);

-- Deliberately independent of account FKs: retries survive local account deletion.
CREATE TABLE IF NOT EXISTS account_inactivity (
 identity_id VARCHAR(36) NOT NULL PRIMARY KEY,
 tenant_id BIGINT NULL,
 assigned_months INT NOT NULL,
 revision BIGINT NOT NULL,
 last_activity DATETIME(6) NOT NULL,
 due_at DATETIME(6) NOT NULL,
 status VARCHAR(20) NOT NULL,
 last_error VARCHAR(1000) NULL,
 attempts INT NOT NULL DEFAULT 0,
 INDEX inactivity_due (status,due_at,identity_id)
);
-- No trustworthy historic activity exists: use this rollout instant, not profile updates.
INSERT IGNORE INTO account_inactivity(identity_id,tenant_id,assigned_months,revision,last_activity,due_at,status)
SELECT identity_id, MIN(tenant_id),24,0,rollout.rollout_at,DATEADD('MONTH',24,rollout.rollout_at),'ACTIVE'
FROM (SELECT user_id AS identity_id,tenant_id FROM `user`
 UNION ALL SELECT consultant_id,tenant_id FROM consultant
 UNION ALL SELECT admin_id,tenant_id FROM admin) identities
CROSS JOIN account_inactivity_rollout rollout
WHERE rollout.id=1
GROUP BY identity_id,rollout.rollout_at;

CREATE TABLE IF NOT EXISTS account_inactivity_journal (
 journal_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 identity_id VARCHAR(36) NOT NULL,
 action VARCHAR(30) NOT NULL,
 target VARCHAR(30) NOT NULL,
 outcome VARCHAR(40) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 INDEX inactivity_journal_identity (identity_id,journal_id)
);

-- No account foreign keys: recovery metadata survives partial deletion and commits independently.
-- DefaultAccountInactivityEffects deletes these rows in the transaction confirming DELETED.
CREATE TABLE IF NOT EXISTS account_inactivity_access_state (
 identity_id VARCHAR(36) NOT NULL PRIMARY KEY,
 keycloak_enabled BOOLEAN NOT NULL,
 restored BOOLEAN NOT NULL,
 deletion_authorized BOOLEAN NOT NULL
);
CREATE TABLE IF NOT EXISTS account_inactivity_matrix_state (
 identity_id VARCHAR(36) NOT NULL,
 matrix_user_id VARCHAR(255) NOT NULL,
 original_locked BOOLEAN NOT NULL,
 PRIMARY KEY(identity_id,matrix_user_id)
);

-- Spring Security jwt() uses this explicit synthetic subject in authorization-only tests.
INSERT IGNORE INTO account_inactivity(identity_id,tenant_id,assigned_months,revision,last_activity,due_at,status) VALUES('user',1,24,0,CURRENT_TIMESTAMP(6),DATEADD('MONTH',24,CURRENT_TIMESTAMP(6)),'ACTIVE');
