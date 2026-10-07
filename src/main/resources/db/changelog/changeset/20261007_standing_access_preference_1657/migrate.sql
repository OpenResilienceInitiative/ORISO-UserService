ALTER TABLE `session` ADD COLUMN IF NOT EXISTS always_ask_before_additional_access BIT NOT NULL DEFAULT FALSE;
