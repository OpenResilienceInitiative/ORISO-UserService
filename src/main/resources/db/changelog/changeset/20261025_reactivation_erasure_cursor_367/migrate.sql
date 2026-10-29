-- Only the existing dedicated lease owns this selection hint. It grants no account authority.
ALTER TABLE scheduled_task_claim
  ADD COLUMN IF NOT EXISTS reactivation_erasure_after_account_id VARCHAR(36) NULL;
