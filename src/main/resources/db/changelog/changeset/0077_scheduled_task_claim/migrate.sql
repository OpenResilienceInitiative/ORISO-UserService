CREATE TABLE IF NOT EXISTS scheduled_task_claim (
  task_name VARCHAR(128) NOT NULL,
  claimed_at DATETIME(6) NOT NULL,
  claimed_until DATETIME(6) NOT NULL,
  PRIMARY KEY (task_name)
);

INSERT INTO scheduled_task_claim (task_name, claimed_at, claimed_until)
VALUES
  ('enquiry-notification', '1970-01-01 00:00:00.000000', '1970-01-01 00:00:00.000000'),
  ('anonymous-user-deactivation', '1970-01-01 00:00:00.000000', '1970-01-01 00:00:00.000000'),
  ('anonymous-user-deletion', '1970-01-01 00:00:00.000000', '1970-01-01 00:00:00.000000'),
  ('account-deletion', '1970-01-01 00:00:00.000000', '1970-01-01 00:00:00.000000')
ON DUPLICATE KEY UPDATE task_name = task_name;
