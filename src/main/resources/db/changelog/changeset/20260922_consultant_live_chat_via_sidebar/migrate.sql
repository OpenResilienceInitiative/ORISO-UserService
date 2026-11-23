-- "Live Chat über Menü Leiste aktivieren": a counsellor may control their live-chat
-- availability from the navigation rail instead of My Profile. The preference used
-- to live in the browser only; it now belongs to the profile. Existing rows default
-- to 0, which is the behaviour every counsellor has today.
ALTER TABLE consultant
  ADD COLUMN IF NOT EXISTS live_chat_via_sidebar TINYINT(1) NOT NULL DEFAULT 0;
