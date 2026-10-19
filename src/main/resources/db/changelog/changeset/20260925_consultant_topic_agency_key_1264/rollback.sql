ALTER TABLE consultant_topic
  DROP INDEX uk_consultant_topic_agency_key,
  ADD UNIQUE KEY uk_consultant_topic_agency (consultant_id, topic_id, agency_id);
ALTER TABLE consultant_topic
  DROP COLUMN agency_key;
