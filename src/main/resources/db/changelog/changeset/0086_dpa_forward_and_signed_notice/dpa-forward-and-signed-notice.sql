-- ORISO-UserService#1005 (epic ORISO-Admin#722): DPA forward from the public onboarding wizard
-- and the DPA_SIGNED_NOTICE back to the forwarding administrator.

-- When the tenant-admin onboarding wizard forwarded the DPA to an authorised signer: the
-- server-side proof that registration may proceed without an own acceptance, and the anchor for
-- resolving the notice recipient of a pre-account forward.
ALTER TABLE account_invite
  ADD COLUMN dpa_forwarded_at DATETIME NULL AFTER two_factor_status;

-- Exactly-once ledger for DPA_SIGNED_NOTICE mails: one notice per tenant and signed DPA version.
-- The unique key is the concurrency guarantee - of two parallel signature hints exactly one
-- claims the row and sends the mail.
CREATE TABLE dpa_signed_notice (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  dpa_version VARCHAR(64) NOT NULL,
  recipient_email VARCHAR(255) NOT NULL,
  signed_at DATETIME NULL,
  sent_at DATETIME NULL,
  create_date DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_dpa_signed_notice_tenant_version (tenant_id, dpa_version)
);
