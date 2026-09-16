-- Deliberately independent of account FKs: retries survive local account deletion.
CREATE TABLE account_inactivity (
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
INSERT INTO account_inactivity(identity_id,tenant_id,assigned_months,revision,last_activity,due_at,status)
SELECT identity_id, MIN(tenant_id),24,0,rollout.rollout_at,DATE_ADD(rollout.rollout_at,INTERVAL 24 MONTH),'ACTIVE'
FROM (SELECT user_id AS identity_id,tenant_id FROM `user`
 UNION ALL SELECT consultant_id,tenant_id FROM consultant
 UNION ALL SELECT admin_id,tenant_id FROM admin) identities
CROSS JOIN account_inactivity_rollout rollout
WHERE rollout.id=1
GROUP BY identity_id,rollout.rollout_at;

CREATE TABLE account_inactivity_journal (
 journal_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 identity_id VARCHAR(36) NOT NULL,
 action VARCHAR(30) NOT NULL,
 target VARCHAR(30) NOT NULL,
 outcome VARCHAR(40) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 INDEX inactivity_journal_identity (identity_id,journal_id)
);
