-- Collapse per-centre rows back to one row per (consultant, topic) before restoring the old key.
DELETE t1 FROM consultant_topic t1
  JOIN consultant_topic t2
    ON t1.consultant_id = t2.consultant_id AND t1.topic_id = t2.topic_id AND t1.id > t2.id;
ALTER TABLE consultant_topic
  DROP INDEX uk_consultant_topic_agency,
  ADD UNIQUE KEY uk_consultant_topic (consultant_id, topic_id),
  DROP COLUMN agency_id;
