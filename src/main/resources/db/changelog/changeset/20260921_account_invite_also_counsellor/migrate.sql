-- ORISO-Admin#1026 slice 3: an agency-admin invite says whether the person also counsels. The
-- invitee may change it in the onboarding wizard. NULL for every other role and for old rows.
ALTER TABLE account_invite
  ADD COLUMN IF NOT EXISTS also_counsellor TINYINT(1) NULL;
