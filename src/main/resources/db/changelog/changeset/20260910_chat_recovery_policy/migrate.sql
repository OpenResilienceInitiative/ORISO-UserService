ALTER TABLE `user` ADD COLUMN IF NOT EXISTS chat_recovery_mode VARCHAR(32) NULL;
ALTER TABLE `user` ADD COLUMN IF NOT EXISTS chat_recovery_policy_revision BIGINT NULL;
ALTER TABLE `consultant` ADD COLUMN IF NOT EXISTS chat_recovery_mode VARCHAR(32) NULL;
ALTER TABLE `consultant` ADD COLUMN IF NOT EXISTS chat_recovery_policy_revision BIGINT NULL;
