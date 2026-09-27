-- #200: the co-access colleague may extend their access once. The extension gets its own
-- column instead of the audit outcome, which the expiry sweep and a consent answer overwrite,
-- so the one-extension rule holds and the audit keeps the extension. NULL = not extended.
ALTER TABLE case_handover_request
  ADD COLUMN IF NOT EXISTS extended_at DATETIME NULL;
