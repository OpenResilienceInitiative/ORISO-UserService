-- PUSH direction for case handover: a counsellor offers their own case to a named colleague.
--
-- The offer is NOT a second table. It is the same case_handover_request row the PULL flow
-- already writes, so audit log, reason policy, client-consent gate and Matrix system message
-- stay on one path — the row simply records who started it and who it was aimed at.
--
--   direction            PULL (the requester wants someone else's case, the default and every
--                        historical row) or PUSH (the owner hands their own case over).
--   target_consultant_id the colleague the offer was made to. On a PUSH row it equals
--                        requester_consultant_id: acceptance makes the target the new owner, and
--                        the grant path reads requester_consultant_id, so the two must agree.
--                        Kept as its own column anyway because it is what the audit log means by
--                        "offered to", and it stays meaningful if the grant path ever changes.
--   offer_expires_at     72 hours after creation. The expiry scheduler moves untouched offers to
--                        EXPIRED so a case is never silently parked in someone else's inbox.
--
-- No foreign key on target_consultant_id on purpose: MariaDB has no ADD CONSTRAINT IF NOT EXISTS,
-- so a FK would make this changeset non-idempotent — and case_handover_request is exactly the
-- table where a re-run has already happened (0057 MARK_RAN, 0059 repair). Referential integrity
-- for the column is enforced by the JPA association, as it is for previous_consultant_id.
--
-- Idempotent and precondition-free (0057/0059 lesson): safe to re-run on every environment.

ALTER TABLE case_handover_request
  ADD COLUMN IF NOT EXISTS direction VARCHAR(8) NOT NULL DEFAULT 'PULL';

ALTER TABLE case_handover_request
  ADD COLUMN IF NOT EXISTS target_consultant_id VARCHAR(36) NULL;

ALTER TABLE case_handover_request
  ADD COLUMN IF NOT EXISTS offer_expires_at DATETIME NULL;

-- Offers are read by recipient and by expiry sweep; both are hot paths on the consultant's
-- session list badge.
CREATE INDEX IF NOT EXISTS idx_case_handover_target_status
  ON case_handover_request (target_consultant_id, status);

CREATE INDEX IF NOT EXISTS idx_case_handover_status_expires
  ON case_handover_request (status, offer_expires_at);
