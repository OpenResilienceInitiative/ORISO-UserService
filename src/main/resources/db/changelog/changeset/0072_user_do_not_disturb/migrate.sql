-- Global per-user Do-Not-Disturb (decided 2026-07-18). dnd_until in the future = active;
-- auto-reverts when the timestamp passes (no cleanup job). Announcements (toast/sound/push)
-- and notification emails are suppressed while active; the persisted activity feed still fills.
CREATE TABLE IF NOT EXISTS user_do_not_disturb (
  user_id VARCHAR(64) NOT NULL,
  dnd_until DATETIME NULL,
  update_date DATETIME NOT NULL,
  PRIMARY KEY (user_id)
);
