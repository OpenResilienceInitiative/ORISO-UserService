CREATE TABLE IF NOT EXISTS scheduled_task_claim (
  task_name VARCHAR(128) NOT NULL,
  claimed_at DATETIME(6) NOT NULL,
  claimed_until DATETIME(6) NOT NULL,
  PRIMARY KEY (task_name)
);
