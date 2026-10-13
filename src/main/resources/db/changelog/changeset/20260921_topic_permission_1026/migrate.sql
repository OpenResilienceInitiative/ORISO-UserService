-- ORISO-Admin#1026, slice 6: how far a counsellor may extend their own topics.
-- NONE = only the assigned department, SELECT_EXISTING = pick among the agency's
-- departments, CREATE = add further topics (the onboarding "+", today's behaviour).
-- Every existing invite and counsellor gets CREATE, so nothing changes for them;
-- new invites are written with an explicit value by the application.
ALTER TABLE account_invite
  ADD COLUMN IF NOT EXISTS topic_permission VARCHAR(32) NOT NULL DEFAULT 'CREATE';
ALTER TABLE consultant
  ADD COLUMN IF NOT EXISTS topic_permission VARCHAR(32) NOT NULL DEFAULT 'CREATE';
