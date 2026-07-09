-- Columns that exist on live databases only via manual ALTERs — no Liquibase
-- changeset ever created them, so a fresh bootstrap does not match the JPA
-- entities (found 2026-07-04 while bootstrapping the full platform from empty
-- databases: UserService was the only service that additionally needed
-- ddl-auto=update). Types match the entity definitions. Idempotent so it is
-- safe on databases where the columns were already added by hand.

-- Chat.matrixRoomId
ALTER TABLE chat ADD COLUMN IF NOT EXISTS matrix_room_id VARCHAR(255) NULL;

-- Session.matrixRoomId
ALTER TABLE session ADD COLUMN IF NOT EXISTS matrix_room_id VARCHAR(255) NULL;

-- User.matrixUserId, User.magicLinkLoginEnabled
ALTER TABLE user ADD COLUMN IF NOT EXISTS matrix_user_id VARCHAR(255) NULL;
ALTER TABLE user ADD COLUMN IF NOT EXISTS magic_link_login_enabled BIT NOT NULL DEFAULT 0;

-- Consultant.matrixUserId, Consultant.displayName, Consultant.isSupervisor,
-- Consultant.magicLinkLoginEnabled
ALTER TABLE consultant ADD COLUMN IF NOT EXISTS matrix_user_id VARCHAR(255) NULL;
ALTER TABLE consultant ADD COLUMN IF NOT EXISTS display_name VARCHAR(255) NULL;
ALTER TABLE consultant ADD COLUMN IF NOT EXISTS is_supervisor TINYINT NOT NULL DEFAULT 0;
ALTER TABLE consultant ADD COLUMN IF NOT EXISTS magic_link_login_enabled BIT NOT NULL DEFAULT 0;
