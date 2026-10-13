-- #1526: product tours become an opt-in switch under Profile -> Help.
-- Only the column default changes: new counsellors start with tours off,
-- existing counsellors keep their current value. Safe to run twice.
ALTER TABLE consultant ALTER COLUMN walk_through_enabled SET DEFAULT 0;
