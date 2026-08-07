-- #942: create_date had second precision only — fan-out bursts write many
-- rows in the same second and the feed order flipped between two polls.
-- Millisecond precision plus the id tiebreaker in the repository query
-- make the ordering deterministic. Existing rows keep their timestamps.
ALTER TABLE userservice.event_notification
  MODIFY create_date DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  MODIFY read_date DATETIME(3) NULL;
