ALTER TABLE account_invite
  ADD COLUMN provisioning_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' AFTER status,
  ADD COLUMN provisioned_user_id VARCHAR(36) NULL AFTER provisioning_status,
  ADD COLUMN provisioning_failure_reason VARCHAR(1024) NULL AFTER provisioned_user_id;
