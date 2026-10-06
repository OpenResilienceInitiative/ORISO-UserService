-- Built-in system default invite templates: counsellor invite and Träger invite, in German
-- and English. Owned by the platform (tenant_id NULL), active, marked system_default, so an
-- invite e-mail is never empty because nobody wrote a template.
--
-- Idempotent: a row is only inserted when no system default exists for that kind and
-- language yet, so an edited default is never duplicated or overwritten.
--
-- The bodies carry no {{inviteLink}}: the branded layout renders the link as a button.
-- No semicolons or backslashes inside the texts (statement splitting, MariaDB escapes).

INSERT INTO invite_email_template
  (tenant_id, kind, name, language, subject, body, active, system_default, created_by_user_id,
   create_date, update_date)
SELECT NULL, 'COUNSELLOR_INVITE', 'Standardvorlage Berater-Einladung (Deutsch)', 'de',
  'Ihre Einladung als Berater:in',
  'Hallo {{firstName}} {{lastName}},

Sie wurden eingeladen, als Berater:in in der Online-Beratung mitzuarbeiten.

Bitte richten Sie Ihr Konto über den Button in dieser E-Mail ein.

Dieser Link ist nur für Sie bestimmt. Bitte leiten Sie ihn nicht weiter. Wenn Sie diese Einladung nicht erwartet haben, können Sie diese E-Mail ignorieren.',
  TRUE, TRUE, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM (SELECT 1 AS seed_row) seed
WHERE NOT EXISTS (
  SELECT 1 FROM invite_email_template t
  WHERE t.system_default = TRUE AND t.tenant_id IS NULL
    AND t.kind = 'COUNSELLOR_INVITE' AND t.language = 'de');

INSERT INTO invite_email_template
  (tenant_id, kind, name, language, subject, body, active, system_default, created_by_user_id,
   create_date, update_date)
SELECT NULL, 'COUNSELLOR_INVITE', 'Default counsellor invite (English)', 'en',
  'Your invitation as a counsellor',
  'Hello {{firstName}} {{lastName}},

You have been invited to join the online counselling service as a counsellor.

Please set up your account with the button in this e-mail.

This link is meant for you only. Please do not forward it. If you did not expect this invitation, you can ignore this e-mail.',
  TRUE, TRUE, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM (SELECT 1 AS seed_row) seed
WHERE NOT EXISTS (
  SELECT 1 FROM invite_email_template t
  WHERE t.system_default = TRUE AND t.tenant_id IS NULL
    AND t.kind = 'COUNSELLOR_INVITE' AND t.language = 'en');

INSERT INTO invite_email_template
  (tenant_id, kind, name, language, subject, body, active, system_default, created_by_user_id,
   create_date, update_date)
SELECT NULL, 'TENANT_INVITE', 'Standardvorlage Träger-Einladung (Deutsch)', 'de',
  'Ihre Einladung zur Verwaltung Ihres Trägers',
  'Hallo {{firstName}} {{lastName}},

Sie wurden eingeladen, Ihren Träger auf der Online-Beratungsplattform zu verwalten.

Bitte richten Sie Ihr Konto über den Button in dieser E-Mail ein. Danach führt Sie die Einrichtung Schritt für Schritt durch die Angaben zu Ihrem Träger.

Dieser Link ist nur für Sie bestimmt. Bitte leiten Sie ihn nicht weiter. Wenn Sie diese Einladung nicht erwartet haben, können Sie diese E-Mail ignorieren.',
  TRUE, TRUE, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM (SELECT 1 AS seed_row) seed
WHERE NOT EXISTS (
  SELECT 1 FROM invite_email_template t
  WHERE t.system_default = TRUE AND t.tenant_id IS NULL
    AND t.kind = 'TENANT_INVITE' AND t.language = 'de');

INSERT INTO invite_email_template
  (tenant_id, kind, name, language, subject, body, active, system_default, created_by_user_id,
   create_date, update_date)
SELECT NULL, 'TENANT_INVITE', 'Default organisation invite (English)', 'en',
  'Your invitation to manage your organisation',
  'Hello {{firstName}} {{lastName}},

You have been invited to manage your organisation on the online counselling platform.

Please set up your account with the button in this e-mail. The setup then guides you step by step through the details of your organisation.

This link is meant for you only. Please do not forward it. If you did not expect this invitation, you can ignore this e-mail.',
  TRUE, TRUE, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM (SELECT 1 AS seed_row) seed
WHERE NOT EXISTS (
  SELECT 1 FROM invite_email_template t
  WHERE t.system_default = TRUE AND t.tenant_id IS NULL
    AND t.kind = 'TENANT_INVITE' AND t.language = 'en');
