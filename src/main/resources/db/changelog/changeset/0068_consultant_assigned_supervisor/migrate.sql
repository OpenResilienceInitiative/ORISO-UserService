-- grill 2026-07-13: "Supervision (auto-assigned)" — the standing supervisor an agency admin
-- sets per counsellor (Consultant.assignedSupervisorId). Every Agency Counselling case that
-- counsellor accepts auto-attaches this colleague read-only. NULL = no standing supervision.
-- Plain id (matches consultant.consultant_id VARCHAR(36)); no FK constraint, so a deleted
-- supervisor degrades to a no-op attach rather than blocking the delete. Idempotent.
ALTER TABLE consultant ADD COLUMN IF NOT EXISTS assigned_supervisor_id VARCHAR(36) NULL;
