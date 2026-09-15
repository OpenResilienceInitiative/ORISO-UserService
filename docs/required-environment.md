# Required environment

The variables UserService refuses to start without, and why each one is guarded.
`config.env.example` carries all of them; this page is the reasoning behind it.

Answers issue
[#1099](https://github.com/OpenResilienceInitiative/ORISO-UserService/issues/1099).

## Getting a local service running

```bash
cp config.env.example config.env      # then fill every CHANGE_ME
cp run-local-remote-db.sh.example run-local-remote-db.sh
chmod +x run-local-remote-db.sh
./run-local-remote-db.sh
```

Full walkthrough, including the frontend pairing and the dev CA truststore:
[`documentation/local-development.md`](../documentation/local-development.md).

## The startup guards

Each of these aborts the Spring context in `@PostConstruct`, before a single
request is served. They fail one at a time, so a missing set is met one restart
at a time.

| Variable | Guard | Without it |
|---|---|---|
| `STATISTICS_MESSAGE_COUNT_HMAC_SECRET` | `ConsultantIdentityHasher` | `IllegalStateException` naming the variable |
| `MATRIXRTC_CALL_POLICY_HMAC_SECRET` | `MatrixRtcCorrelationIdHasher` | `IllegalStateException` naming the variable |
| `SPRING_LIQUIBASE_ENABLED` / `ORISO_MIGRATIONS_EXTERNALLY_MANAGED` | `SchemaMigrationGuard` | `IllegalStateException`: changesets in the image would never be applied |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `SchemaMigrationGuard` | `IllegalStateException` unless the value is `validate` |

`SPRING_DATASOURCE_URL` is required too, but the local path gets it from
`run-local-remote-db.sh`, not from `config.env`. A value in `config.env`
overrides it, because the script sources that file after its own exports.

### The two HMAC secrets

Both pseudonymize an identifier that would otherwise be readable by anyone with
database or log access: a consultant id in message-count statistics, and a
room/user pair in MatrixRTC call-policy denial logs. Both reject a blank value
rather than falling back to a default, because a shared or empty key would make
the pseudonymization decorative.

Locally any random value works — `openssl rand -hex 32`. Keep them distinct from
each other and from `service.encryption.appkey`; that independence is what stops
one leaked secret from unmasking the other purpose. In a real deployment
`MATRIXRTC_CALL_POLICY_HMAC_SECRET` must match the platform-wide MatrixRTC
secret, or correlation ids stop lining up between the services that log them.

### The migration settings

`SchemaMigrationGuard` refuses a deployment that would run against a schema
nobody migrates or verifies. The reasoning, the escape hatch and how to audit an
environment are in [`schema-migrations.md`](schema-migrations.md).

For local work the shipped example sets `SPRING_LIQUIBASE_ENABLED=false` with
`ORISO_MIGRATIONS_EXTERNALLY_MANAGED=true`, because the documented local setup
talks to the **remote dev database**, which the deployed dev UserService
migrates. A laptop running Liquibase against it would apply changesets — and in
time the seed/demo changesets — to a database the whole team shares, and an
interrupted run would leave `DATABASECHANGELOGLOCK` held against the real
deployment. Against a database only you use, set `SPRING_LIQUIBASE_ENABLED=true`
and drop the escape hatch.

`SPRING_JPA_HIBERNATE_DDL_AUTO` must stay `validate` either way. That check is
the one that catches an entity change nobody wrote a changeset for, so the guard
accepts no other value — `update` and `create` let Hibernate alter the schema
behind Liquibase's back, and `none` skips the comparison entirely.

## Adding the next required variable

All three variables in #1099 slipped through because nothing tied a new startup
guard to the example file. `ConfigEnvExampleContractTest` now does: it fails the
build when a guard in `src/main/java` names an environment variable that
`config.env.example` does not declare.

It finds them by convention — the message ends `must be set (THE_ENV_VAR)`, as
in:

```java
throw new IllegalStateException(
    "statistics.message-count.hmac-secret must be set (STATISTICS_MESSAGE_COUNT_HMAC_SECRET)");
```

So a new guard needs three things: that message shape, a placeholder line in
`config.env.example`, and a row in the table above. The cluster side is separate
— see [ORISO-Helm#272](https://github.com/OpenResilienceInitiative/ORISO-Helm/issues/272).
