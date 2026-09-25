-- ORISO-Admin#1026: remember how an invite's tenant and agency IDs were allocated. EXISTING marks
-- an invite into a Träger / Beratungsstelle that already exists: its onboarding joins the unit
-- instead of creating it. Existing rows stay NULL (pre-#1026 behaviour: a new unit).
ALTER TABLE account_invite
  ADD COLUMN IF NOT EXISTS tenant_id_allocation_mode VARCHAR(16) NULL,
  ADD COLUMN IF NOT EXISTS agency_id_allocation_mode VARCHAR(16) NULL;
