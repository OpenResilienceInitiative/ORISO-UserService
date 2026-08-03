ALTER TABLE account_invite
  DROP COLUMN provisioning_failure_reason,
  DROP COLUMN provisioned_user_id,
  DROP COLUMN provisioning_status;
