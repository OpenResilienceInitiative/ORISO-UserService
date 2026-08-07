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
