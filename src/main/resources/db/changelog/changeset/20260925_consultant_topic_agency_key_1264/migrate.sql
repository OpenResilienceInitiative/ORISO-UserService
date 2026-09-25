-- #1264: MariaDB treats NULLs in a unique key as distinct, so (consultant_id, topic_id, agency_id)
-- let a counsellor hold the same topic twice without a centre. agency_key maps "no centre" to -1
-- (agency ids are positive; 0 is a real seeded agency), and the unique key uses it instead.
-- Existing rows are already unique per (consultant_id, topic_id), so the new key cannot collide.
ALTER TABLE consultant_topic
  ADD COLUMN IF NOT EXISTS agency_key BIGINT(21) AS (COALESCE(agency_id, -1)) PERSISTENT AFTER agency_id;
ALTER TABLE consultant_topic
  DROP INDEX uk_consultant_topic_agency,
  ADD UNIQUE KEY uk_consultant_topic_agency_key (consultant_id, topic_id, agency_key);
