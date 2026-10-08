-- The animal an advice seeker picks in their profile (#1240). Null keeps the default the app
-- derives from the user id, so existing rows need no value.
ALTER TABLE `user` ADD COLUMN IF NOT EXISTS avatar_id VARCHAR(40) NULL;
