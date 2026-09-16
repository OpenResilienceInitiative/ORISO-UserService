CREATE TABLE account_inactivity_rollout (
 id INT NOT NULL PRIMARY KEY,
 rollout_at DATETIME(6) NOT NULL,
 last_scan DATETIME(6) NULL,
 inventory_complete BOOLEAN NOT NULL DEFAULT FALSE,
 enrolled INT NOT NULL DEFAULT 0,
 missing_new INT NOT NULL DEFAULT 0,
 failed INT NOT NULL DEFAULT 0
);
INSERT INTO account_inactivity_rollout(id,rollout_at) VALUES(1,UTC_TIMESTAMP(6));
CREATE TABLE account_inactivity_bootstrap_issue (
 identity_id VARCHAR(36) NOT NULL PRIMARY KEY,
 reason VARCHAR(40) NOT NULL,
 observed_at DATETIME(6) NOT NULL
);
