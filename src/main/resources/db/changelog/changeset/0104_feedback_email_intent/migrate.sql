ALTER TABLE reply_email_delivery
  ADD COLUMN source_event_id VARCHAR(255) NULL AFTER source_room_id;
