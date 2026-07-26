INSERT INTO scheduled_task_claim (task_name, claimed_at, claimed_until)
VALUES
  ('inactive-session-deletion', '1970-01-01 00:00:00.000000', '1970-01-01 00:00:00.000000')
ON DUPLICATE KEY UPDATE task_name = task_name;
