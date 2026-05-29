DROP INDEX idx_invite_link_consultant        ON agency_invite_link;
DROP INDEX idx_invite_link_kind_tenant       ON agency_invite_link;
DROP INDEX idx_invite_link_topic_create_date ON agency_invite_link;

ALTER TABLE agency_invite_link
  MODIFY agency_id BIGINT NOT NULL,
  DROP COLUMN consultant_id,
  DROP COLUMN notes,
  DROP COLUMN anonymity,
  DROP COLUMN chat_type,
  DROP COLUMN link_kind,
  DROP COLUMN topic_id;
