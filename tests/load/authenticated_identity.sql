-- This human JWT subject exists only in the disposable replica proof, so the
-- migration's account-table backfill cannot discover it. Give it the ordinary
-- creation-time policy before exercising authenticated requests on either pod.
INSERT INTO account_inactivity
 (identity_id,tenant_id,assigned_months,revision,last_activity,due_at,status)
VALUES
 ('tutorial-replica-jwt-user',1,24,0,UTC_TIMESTAMP(6),
  DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 24 MONTH),'ACTIVE');
