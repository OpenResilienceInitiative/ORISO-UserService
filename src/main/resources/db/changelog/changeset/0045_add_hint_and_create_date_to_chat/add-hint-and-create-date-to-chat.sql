ALTER TABLE `userservice`.`chat`
    ADD COLUMN IF NOT EXISTS `hint_message` VARCHAR(300) NULL DEFAULT NULL;
ALTER TABLE `userservice`.`chat`
    ADD COLUMN IF NOT EXISTS `create_date` datetime NOT NULL DEFAULT (UTC_TIMESTAMP);