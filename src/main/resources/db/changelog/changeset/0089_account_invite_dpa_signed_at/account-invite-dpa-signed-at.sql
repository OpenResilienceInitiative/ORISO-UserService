-- ORISO-Admin#896 (epic #725): the Admin invite progress board proves its final
-- "Vertrag unterschrieben" phase from the invite row. The authoritative signature record stays
-- TenantService's tenant_dpa_signature; this column is the invite-side write-back, stamped by the
-- DPA_SIGNED_NOTICE chain (forwarded signatures, ORISO-UserService#1005) and by the onboarding
-- registration (own acceptance). Nullable on purpose: null means "not signed yet", and the
-- write-back only ever fills a null - it never overwrites or clears.
ALTER TABLE account_invite
  ADD COLUMN IF NOT EXISTS dpa_signed_at DATETIME NULL AFTER dpa_forward_count;
