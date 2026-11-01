# Invitation template deletion

Deleting a custom invitation template removes it from future selection. It preserves sent
invitations and their stored subject/body snapshots. A template still selected by a waiting
invitation cannot be deleted. System defaults cannot be deleted.

Scoped invitation admins see their own Träger's templates and platform system defaults. Legacy
platform custom templates remain in the platform catalogue. The same rule protects preview and
send requests that supply a template ID directly. Ownership remains by Träger, not by author.

## Deployment preflight

The migration adds a restrictive foreign key only to the transient queue reference. It halts
if any old reference names a missing template. It does not repair or remove existing rows.
Run this read-only query on the intended environment before deployment; any result needs an
explicit owner decision. Live data has not been checked by this local implementation.

**For operators — identify references that would prevent the migration:**

```sql
SELECT i.id, i.status, i.queued_template_id
FROM account_invite i
LEFT JOIN invite_email_template t ON t.id = i.queued_template_id
WHERE i.queued_template_id IS NOT NULL AND t.id IS NULL;
```

## API contract

**For developers — deletion results and preserved state:**

```text
DELETE /useradmin/invite-email-templates/{templateId}
204: custom template deleted with existing owner/kind update policy
403: caller lacks invitation admin authority, owner/kind is outside scope, or system default
404: template missing
409: waiting invite references template, including concurrent foreign-key conflict

After normal queue release: queued_template_id is cleared; delivery history retains template_id.
During deletion: clear only target-template references on non-WAITING_FOR_UNIT invitations.
No invitation status, subject/body snapshot, recipient, or account is changed by deletion.
```

## Local database fixtures

The scope/deletion fixture always uses a fresh embedded H2 database, even when generic datasource
environment variables are set. The migration fixture defaults to fresh H2 too. Its optional MariaDB
connection is restricted to loopback and the dedicated disposable schema; URL credentials and
options are refused before opening a connection. No external credential defaults are provided.

**For developers — optional disposable MariaDB fixture settings:**

```text
TEMPLATE_DELETE_IT_DB_URL=jdbc:mariadb://127.0.0.1:<port>/template_delete_test
TEMPLATE_DELETE_IT_DB_USER and TEMPLATE_DELETE_IT_DB_PASSWORD must both be set.
Only localhost or 127.0.0.1 and the exact template_delete_test schema are accepted.
The migration fixture resets tables in that schema; use a disposable database only.
```
