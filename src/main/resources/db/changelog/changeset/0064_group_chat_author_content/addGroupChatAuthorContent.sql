ALTER TABLE chat
  ADD COLUMN source_language VARCHAR(10) NULL,
  ADD COLUMN hint_message_translations JSON NULL,
  ADD COLUMN group_chat_rules_translations JSON NULL;
