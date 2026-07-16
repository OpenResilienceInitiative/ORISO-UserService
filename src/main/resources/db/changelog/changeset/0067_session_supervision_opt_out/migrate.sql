-- grill 2026-07-13: the ratsuchende's supervision opt-out (Session.supervisionOptedOut),
-- replacing the retired per-reason supervision consent gate. Supervision is allowed by
-- default (0); when the client switches this on, no supervisor may be attached and any
-- active supervisors are removed. Idempotent so it is safe where the column was added by hand.
ALTER TABLE session ADD COLUMN IF NOT EXISTS is_supervision_opted_out BIT NOT NULL DEFAULT 0;
