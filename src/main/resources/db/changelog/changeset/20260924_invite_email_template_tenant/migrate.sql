-- Invite e-mail templates get an owning Träger (ORISO-Admin#1026).
--
-- Creating a template is open to every admin who may send invites. Without an owner
-- that is a cross-tenant write: a Beratungsstellen admin of Träger A would store a
-- text Träger B sees in its list and sends to its own people.
--
-- NULL means "platform template": written by the platform operator, offered to every
-- Träger, changeable only by the platform operator. Every row that exists today is
-- one, which is why the column is NULLABLE and is NOT backfilled. A NOT NULL column
-- without a default would also fail every integration test on an empty schema
-- (see the UserService NOT-NULL trap).
ALTER TABLE invite_email_template
  ADD COLUMN IF NOT EXISTS tenant_id BIGINT NULL;

-- The list query filters by tenant and kind; ordering is by create_date.
CREATE INDEX IF NOT EXISTS idx_invite_email_template_tenant
  ON invite_email_template (tenant_id, kind);
