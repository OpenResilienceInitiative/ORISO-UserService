-- Tolerates columns already missing (partial schema) — every drop is guarded.
ALTER TABLE consultant DROP COLUMN IF EXISTS admin_remarks;
ALTER TABLE consultant DROP COLUMN IF EXISTS title;
ALTER TABLE consultant DROP COLUMN IF EXISTS `position`;
ALTER TABLE consultant DROP COLUMN IF EXISTS salutation;
