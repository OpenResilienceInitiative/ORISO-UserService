ALTER TABLE consultant_message_stat
  ADD COLUMN source_event_hash VARCHAR(64) NULL;

CREATE UNIQUE INDEX uk_consultant_message_stat_source_event_hash
  ON consultant_message_stat (source_event_hash);
