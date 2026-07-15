ALTER TABLE event_notification
  ADD COLUMN deduplication_key VARCHAR(191) NULL;

CREATE UNIQUE INDEX uk_event_notification_recipient_deduplication
  ON event_notification (recipient_user_id, deduplication_key);
