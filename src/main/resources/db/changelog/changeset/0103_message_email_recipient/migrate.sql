ALTER TABLE reply_email_delivery
  ADD COLUMN recipient_kind VARCHAR(16) NOT NULL DEFAULT 'ASKER' AFTER recipient_user_id,
  ADD COLUMN source_matrix_user_id VARCHAR(255) NULL AFTER recipient_kind,
  ADD COLUMN source_room_id VARCHAR(255) NULL AFTER source_matrix_user_id;

ALTER TABLE reply_email_delivery
  DROP INDEX uk_reply_email_recipient_event,
  ADD CONSTRAINT uk_reply_email_kind_recipient_event
    UNIQUE (recipient_kind, recipient_user_id, event_key);
