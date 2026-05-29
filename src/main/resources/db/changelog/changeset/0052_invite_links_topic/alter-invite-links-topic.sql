-- Invite Links redesign — topic-based, agency removed.
-- Adds the six new classifier columns required by the new flow, backfills
-- defaults for any pre-existing rows, then enforces NOT NULL on the three
-- columns that always carry a value going forward (link_kind, chat_type,
-- anonymity). agency_id is relaxed to NULL because the new flow no longer
-- requires an agency at create time — it is filled later when a counsellor
-- accepts the resulting session. topic_id stays nullable in the schema for
-- the benefit of any legacy rows; the application layer enforces it on
-- every new insert.

ALTER TABLE agency_invite_link
  ADD COLUMN topic_id      BIGINT       NULL,
  ADD COLUMN link_kind     VARCHAR(32)  NULL,
  ADD COLUMN chat_type     VARCHAR(32)  NULL,
  ADD COLUMN anonymity     VARCHAR(16)  NULL,
  ADD COLUMN notes         VARCHAR(500) NULL,
  ADD COLUMN consultant_id VARCHAR(36)  NULL;

UPDATE agency_invite_link SET link_kind = 'EXTERNAL_INBOUND' WHERE link_kind IS NULL;
UPDATE agency_invite_link SET chat_type = 'LIVE_CHAT'        WHERE chat_type IS NULL;
UPDATE agency_invite_link SET anonymity = 'FULL'             WHERE anonymity IS NULL;

ALTER TABLE agency_invite_link
  MODIFY link_kind VARCHAR(32) NOT NULL,
  MODIFY chat_type VARCHAR(32) NOT NULL,
  MODIFY anonymity VARCHAR(16) NOT NULL;

ALTER TABLE agency_invite_link
  MODIFY agency_id BIGINT NULL;

CREATE INDEX idx_invite_link_topic_create_date ON agency_invite_link (topic_id, create_date);
CREATE INDEX idx_invite_link_kind_tenant       ON agency_invite_link (link_kind, tenant_id, create_date);
CREATE INDEX idx_invite_link_consultant        ON agency_invite_link (consultant_id);
