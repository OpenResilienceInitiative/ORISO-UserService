-- ORISO-UserService#1005: bound how often one onboarding invite may forward the DPA.
-- The forward endpoint is anonymous - the invite token in the path is the only credential - and
-- every call mints a fresh TenantService sign token and mails a live signing link to whatever
-- address the body carries. Without a bound that is an unauthenticated mail relay that delivers a
-- valid signing link for someone else's tenant to an arbitrary recipient.
ALTER TABLE account_invite
  ADD COLUMN IF NOT EXISTS dpa_forward_count INT NOT NULL DEFAULT 0 AFTER dpa_forwarded_at;
