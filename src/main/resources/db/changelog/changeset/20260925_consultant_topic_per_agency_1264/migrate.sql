-- #1264: a counsellor offers a topic at a specific counselling centre (Fachbereich = centre x topic).
-- No backfill: existing rows keep agency_id NULL, which means "every centre of the counsellor"
-- (today's behaviour). The next admin save of the counsellor's topics rewrites them per centre.
ALTER TABLE consultant_topic
  ADD COLUMN IF NOT EXISTS agency_id BIGINT(21) NULL AFTER topic_id;
ALTER TABLE consultant_topic
  DROP INDEX uk_consultant_topic,
  ADD UNIQUE KEY uk_consultant_topic_agency (consultant_id, topic_id, agency_id);
