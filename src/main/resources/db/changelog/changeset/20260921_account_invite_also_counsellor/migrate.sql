-- An agency-admin invite says whether the person also counsels; the invitee may change it in
-- the onboarding wizard. NULL for every other role and for old rows.
ALTER TABLE account_invite
  ADD COLUMN IF NOT EXISTS also_counsellor TINYINT(1) NULL;
