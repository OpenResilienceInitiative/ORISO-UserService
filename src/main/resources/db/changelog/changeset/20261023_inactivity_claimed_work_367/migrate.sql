-- Owning recovery metadata on the existing inactivity row. Never stores a bearer or password.
ALTER TABLE account_inactivity
 ADD COLUMN work_id VARCHAR(36) NULL,
 ADD COLUMN claimed_at DATETIME(6) NULL,
 ADD COLUMN claimed_json LONGTEXT NULL,
 ADD COLUMN source_json LONGTEXT NULL,
 ADD COLUMN native_phase VARCHAR(24) NULL,
 ADD COLUMN native_body LONGTEXT NULL,
 ADD COLUMN native_receipt LONGTEXT NULL,
 ADD COLUMN suspension_operation_id VARCHAR(36) NULL,
 ADD COLUMN suspension_body LONGTEXT NULL,
 ADD COLUMN suspension_receipt LONGTEXT NULL,
 ADD COLUMN access_work_id VARCHAR(36) NULL,
 ADD COLUMN restore_effects_json LONGTEXT NULL;
