# Schema migrations on the cluster

Answers issue
[#458](https://github.com/OpenResilienceInitiative/ORISO-UserService/issues/458)
and the drift-check part of epic
[#351](https://github.com/OpenResilienceInitiative/ORISO-UserService/issues/351).

## The decision: Liquibase on startup (option A)

Migrations run **inside the application on startup**, from
`classpath:db/changelog/userservice-master.xml`. No pre-deploy Job, no
initContainer, no hand-written SQL.

Why this and not a pre-deploy Job: the changesets ship in the same artefact as
the code that needs them, so binding them to the same startup makes it
impossible for the two to arrive separately — which is exactly what went wrong
in #458. A Job is a second thing to schedule, order and monitor, and it can
succeed against a different image than the one that ends up running.

The failure mode people worry about — a migration breaking the rollout — is the
desired behaviour here. A Kubernetes rolling update keeps the previous, working
replica set serving until the new pod is ready, so a failed migration blocks the
new version instead of taking the service down.

## What each mechanism catches

| Mechanism | Catches | On failure |
|---|---|---|
| `spring.liquibase.enabled=true` | changeset shipped, not applied | startup fails, rollout stalls |
| `spring.jpa.hibernate.ddl-auto=validate` | entity changed, **no** changeset written | startup fails, rollout stalls |
| `SchemaMigrationGuard` | someone switched either of the above off | startup fails, rollout stalls |

The second row is the one that is easy to miss. Liquibase running cleanly proves
only that the changesets that exist were applied — not that a developer wrote
one for the column they just added to an entity. Hibernate's `validate` is what
turns that into a boot failure instead of an `Unknown column` 500 per request.

## The escape hatch

`oriso.migrations.externally-managed=true` allows `spring.liquibase.enabled=false`
for setups where a pre-deploy job or a DBA owns the migrations. It has to be set
deliberately, so "we manage migrations elsewhere" and "somebody forgot to turn
Liquibase back on" no longer look the same from the outside.

Hibernate validation stays required either way.

`config.env.example` uses that hatch for local development: the documented local
setup talks to the remote dev database, which the deployed dev UserService
migrates, so a laptop must not run Liquibase against it. Every variable the
service will not start without, this pair included, is listed in
[`required-environment.md`](required-environment.md).

## Auditing an environment

```bash
kubectl get configmap -n <ns> userservice-configmap-env \
  -o jsonpath='{.data.SPRING_LIQUIBASE_ENABLED}{"\n"}'

kubectl exec -n <ns> mariadb-0 -- sh -lc \
  'mariadb -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B -e \
   "select count(*) from userservice.DATABASECHANGELOG;
    select id, exectype, dateexecuted from userservice.DATABASECHANGELOG
    order by orderexecuted desc limit 5;"'
```

State recorded on 2026-08-04, when #458 was closed out:

| Environment | Liquibase | changesets applied | `consultantPublicSlug` |
|---|---|---|---|
| Pre-Dev | enabled | 106 | `EXECUTED` 2026-07-31 14:31 |
| dev | enabled | 102 | `EXECUTED` 2026-07-31 07:57 |

Both carry the `consultant.pending_public_slug` column, and Pre-Dev's startup log
reports `Database is up to date, no changesets to execute`. The manual SQL from
the incident was reconciled into `DATABASECHANGELOG`, so Liquibase does not try
to re-apply it.

## Recovering partial call or rejection migrations

MariaDB can commit the first table of a multi-statement migration before a later
statement fails. A retry must stop on that partial schema: accepting an existing
table alone could conceal missing foreign keys, uniqueness or access-state fields.
Do not edit the released changesets, add blanket `IF NOT EXISTS`, clear checksums,
or mark a changeset as executed merely because a table exists.

This procedure addresses the partial-DDL findings in
[PR 1376](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/1376).
It is a read-only diagnosis and a recovery decision, not permission to change an
operator database. Obtain the separately authorized environment/database access
and take a consistent metadata snapshot before deciding on recovery.

**For operators — exact changeset identities to inspect:**

```text
ID                         AUTHOR  FILENAME
0091-matrix-call-binding    oriso   db/changelog/changeset/0091_matrix_call_binding/0091_changeSet.xml
20261008-enquiry-rejection  oriso   db/changelog/changeset/20261008_enquiry_rejection/changeSet.xml
```

All three identity fields matter. Another changeset with the same numeric prefix
is unrelated. A Git/file hash is not a Liquibase checksum. Validate recorded
checksums with the unchanged release artifact and Liquibase's normal validation;
never substitute a source hash for that check.

**For operators — read-only inspection in an explicitly selected database:**

```sql
SELECT DATABASE();
SELECT ID, AUTHOR, FILENAME, EXECTYPE, MD5SUM, DATEEXECUTED
FROM DATABASECHANGELOG
WHERE (ID = '0091-matrix-call-binding' OR ID = '20261008-enquiry-rejection');

SELECT TABLE_NAME, TABLE_TYPE, TABLE_COLLATION
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('matrix_call_binding', 'matrix_call_invitee',
                     'matrix_call_device', 'enquiry_rejection');

SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT,
       EXTRA, COLLATION_NAME, DATETIME_PRECISION
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('matrix_call_binding', 'matrix_call_invitee',
                     'matrix_call_device', 'enquiry_rejection')
ORDER BY TABLE_NAME, ORDINAL_POSITION;

SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('matrix_call_binding', 'matrix_call_invitee',
                     'matrix_call_device', 'enquiry_rejection')
ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;

SELECT k.TABLE_NAME, k.CONSTRAINT_NAME, k.COLUMN_NAME, k.ORDINAL_POSITION,
       k.REFERENCED_TABLE_SCHEMA, k.REFERENCED_TABLE_NAME,
       k.REFERENCED_COLUMN_NAME, r.DELETE_RULE, r.UPDATE_RULE
FROM information_schema.KEY_COLUMN_USAGE k
LEFT JOIN information_schema.REFERENTIAL_CONSTRAINTS r
  ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
 AND r.TABLE_NAME = k.TABLE_NAME AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME
WHERE k.TABLE_SCHEMA = DATABASE()
  AND k.TABLE_NAME IN ('matrix_call_binding', 'matrix_call_invitee',
                      'matrix_call_device', 'enquiry_rejection')
ORDER BY k.TABLE_NAME, k.CONSTRAINT_NAME, k.ORDINAL_POSITION;

SELECT TABLE_NAME, CONSTRAINT_NAME, CHECK_CLAUSE
FROM information_schema.CHECK_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('matrix_call_binding', 'matrix_call_invitee',
                     'matrix_call_device', 'enquiry_rejection');
```

Compare the complete structures against the original SQL/XML in the release
artifact, including signed/unsigned types, nullability, case-sensitive call IDs,
composite key order, foreign-key targets and delete rules. In particular, the call
binding must retain its exactly-one-source CHECK and both unique room/call keys;
rejection must retain its session foreign key and retry index. A missing ledger
table, denied metadata query or unreadable checksum is missing evidence, never
proof that migration is safe.

| Observed state | Next action |
| --- | --- |
| No ledger identity and no owned objects | Normal pending migration; let the unchanged master run through the authorized deployment path. |
| Exact executed ledger identity and complete compatible structure | Validate checksums with the release artifact; ordinary repeat startup is a no-op. |
| Some objects exist but the migration is unrecorded | Stop. Identify the failed statement and preserve the complete schema/data evidence. |
| Recorded migration has missing or incompatible structure | Stop. Investigate drift; a ledger row does not override actual structure. |
| Complete structure exists without the exact ledger identity | Stop. Establish provenance and an explicitly approved reconciliation; do not infer `MARK_RAN`. |

For a disposable local test database, rebuild only that test-owned database and
apply the unchanged master. For a persistent environment, the operator must plan
restoration or exact forward completion after checking row counts and dependent
foreign keys. Do not drop nonempty or shared objects, run broad schema cleanup,
or use a generic repair SQL script. Record the original failure, release image,
metadata, approved action and post-recovery validation in the incident.

The existing `DatabaseChangelogDriftIT` validates a freshly applied master against
Hibernate, and the MariaDB call/rejection contract tests exercise current behavior.
Those are fresh-schema proofs, not evidence that an arbitrary partial production
schema has been recovered. No live applied-state or recovery claim follows from
this source change.
