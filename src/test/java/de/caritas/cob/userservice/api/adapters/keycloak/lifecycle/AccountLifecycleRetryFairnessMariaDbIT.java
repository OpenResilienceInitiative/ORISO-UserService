package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.core.MariaDBDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Actual InnoDB transactions and migration, using only the disposable hosted proof database. */
@EnabledIfEnvironmentVariable(named = "CALLER_FAIRNESS_DB_URL", matches = ".+")
class AccountLifecycleRetryFairnessMariaDbIT extends AccountLifecycleRetryFairnessSqlTest {
  @Override
  DataSource newDatabase() {
    String url = System.getenv("CALLER_FAIRNESS_DB_URL");
    assertEquals("jdbc:mariadb://127.0.0.1:3306/caller_fairness", url);
    var source =
        new DriverManagerDataSource(url, "root", System.getenv("CALLER_FAIRNESS_DB_PASSWORD"));
    var jdbc = new JdbcTemplate(source);
    for (String table :
        List.of(
            "identity_lifecycle_effect",
            "identity_lifecycle_outbox",
            "identity_lifecycle_attempt",
            "DATABASECHANGELOG",
            "DATABASECHANGELOGLOCK")) jdbc.execute("DROP TABLE IF EXISTS " + table);
    return source;
  }

  @Test
  void thePinnedServerUsesInnoDbAndTheExactDueQueueIndex() {
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                System.getenv("CALLER_FAIRNESS_DB_URL"),
                "root",
                System.getenv("CALLER_FAIRNESS_DB_PASSWORD")));
    String version = jdbc.queryForObject("SELECT VERSION()", String.class);
    assertNotNull(version);
    assertTrue(version.startsWith(System.getenv("CALLER_FAIRNESS_DB_VERSION") + "-"), version);
    System.out.println("Caller fairness native server: " + version);
    assertEquals(
        List.of("InnoDB", "InnoDB", "InnoDB"),
        jdbc.queryForList(
            "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('identity_lifecycle_attempt','identity_lifecycle_outbox','identity_lifecycle_effect') ORDER BY TABLE_NAME",
            String.class));
    assertEquals(
        List.of("status", "next_attempt_at", "created_at", "id"),
        jdbc.queryForList(
            "SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='identity_lifecycle_outbox' AND INDEX_NAME='idx_identity_lifecycle_outbox_due' ORDER BY SEQ_IN_INDEX",
            String.class));
  }

  @Test
  void theActualLiquibaseChangeSetCanRollBackAndUpgradeAnExistingPendingIntent() throws Exception {
    var source = newDatabase();
    var jdbc = new JdbcTemplate(source);
    var database = new MariaDBDatabase();
    database.setConnection(new JdbcConnection(source.getConnection()));
    try (var migration =
        new Liquibase(
            "db/changelog/changeset/20261021_identity_lifecycle_367/changeSet.xml",
            new ClassLoaderResourceAccessor(),
            database)) {
      migration.update(new Contexts(), new LabelExpression());
      jdbc.update(
          "INSERT INTO identity_lifecycle_attempt(id,request_key,identity_json,origin_kind,workflow_id,tenant_id,execution_id,original_execution_id,generation,status,journal_version,update_date) VALUES ('attempt','request','{}','REGISTRATION','workflow','7','execution','execution',0,'COMMIT_REQUESTED',1,?)",
          Timestamp.from(Instant.parse("2026-10-21T12:00:00Z")));
      jdbc.update(
          "INSERT INTO identity_lifecycle_outbox(id,attempt_id,operation,body_json,status,created_at) VALUES ('intent','attempt','COMMIT','immutable-body','PENDING',?)",
          Timestamp.from(Instant.parse("2026-10-21T12:00:00Z")));
      var original =
          jdbc.queryForList(
              "SELECT id,attempt_id,operation,body_json,status,created_at FROM identity_lifecycle_outbox");
      migration.rollback(1, new Contexts(), new LabelExpression());
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='identity_lifecycle_outbox' AND COLUMN_NAME='next_attempt_at'",
              Integer.class));
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='identity_lifecycle_outbox' AND INDEX_NAME='idx_identity_lifecycle_outbox_due'",
              Integer.class));
      assertEquals(
          original,
          jdbc.queryForList(
              "SELECT id,attempt_id,operation,body_json,status,created_at FROM identity_lifecycle_outbox"));
      migration.update(new Contexts(), new LabelExpression());
      assertEquals(
          original,
          jdbc.queryForList(
              "SELECT id,attempt_id,operation,body_json,status,created_at FROM identity_lifecycle_outbox"));
      assertEquals(
          Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")),
          jdbc.queryForObject(
              "SELECT next_attempt_at FROM identity_lifecycle_outbox WHERE id='intent'",
              Timestamp.class));
      migration.rollback(2, new Contexts(), new LabelExpression());
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('identity_lifecycle_attempt','identity_lifecycle_outbox','identity_lifecycle_effect')",
              Integer.class));
    }
  }

  @Test
  void localSecretErasureKeepsTheOriginalDeadlineFullSqlAndUnknownClaim() throws Exception {
    var fixture = retentionFixture();
    var lease = retentionLease(fixture, ERASURE_NOW.minusSeconds(1));
    var original =
        ReactivationUserSnapshotSchema.capture(fixture.jdbc, fixture.codec, ERASURE_ACCOUNT, false);
    assertEquals(fixture.original, original);
    assertEquals(
        0, erasureOwner(fixture, ERASURE_NOW.minusSeconds(1)).eraseExpiredScopedSecrets(lease));
    assertEquals(fixture.frozen, erasureRaw(fixture));
    assertEquals(0, erasureOwner(fixture, ERASURE_NOW).eraseExpiredScopedSecrets(lease));
    assertNull(erasureCursor(fixture));
    assertEquals(1, erasureOwner(fixture, ERASURE_NOW).eraseExpiredScopedSecrets(lease));
    var history = erasureRaw(fixture);
    assertEquals("FROZEN_HISTORY", history.path("phase").textValue());
    assertEquals(fixture.frozen.path("metadata"), history.path("metadata"));
    assertEquals(
        original,
        ReactivationUserSnapshotSchema.capture(
            fixture.jdbc, fixture.codec, ERASURE_ACCOUNT, false));
    assertTrue(history.path("receipt").isNull());
    assertTrue(history.path("postSql").isNull());
    var retained =
        fixture.codec.verifyReactivationHistory(
            history.path("metadata"),
            history.path("envelope").textValue(),
            "FROZEN_HISTORY",
            null,
            ERASURE_ACTOR);
    assertFalse(retained.template().path("change").has("password"));
    assertEquals(
        0,
        erasureOwner(fixture, ERASURE_NOW)
            .eraseExpiredScopedSecrets(
                new ScheduledTaskClaimService.ClaimLease(
                    lease.taskName(), lease.claimedUntil().minusNanos(1000))));
    assertEquals(0, erasureOwner(fixture, ERASURE_NOW).eraseExpiredScopedSecrets(lease));
    assertEquals(history, erasureRaw(fixture));
    assertEquals(
        1,
        fixture.jdbc.queryForObject(
            "select count(*) from scheduled_task_claim where task_name=?",
            Integer.class,
            ScopedAskerReactivation.SECRET_ERASURE_TASK));
  }

  @Test
  void persistedErasureCursorSurvivesPoisonLockedRowsAndTwoColdProcesses() throws Exception {
    var fixture = retentionFixture();
    var poison = new java.util.TreeMap<String, String>();
    for (int index = 0; index < 105; index++) {
      String id = String.format(java.util.Locale.ROOT, "10000000-0000-4000-8000-%012d", index);
      String raw = index == 0 ? "x".repeat(131073) : "{\"incomplete\":";
      fixture.jdbc.update(
          "insert into user(user_id,reactivation_scoped_json) values (?,?)", id, raw);
      poison.put(id, raw);
    }
    var original =
        ReactivationUserSnapshotSchema.capture(fixture.jdbc, fixture.codec, ERASURE_ACCOUNT, false);
    var lease = retentionLease(fixture, ERASURE_NOW);
    assertEquals(0, erasureOwner(fixture, ERASURE_NOW).eraseExpiredScopedSecrets(lease));
    assertEquals("10000000-0000-4000-8000-000000000099", erasureCursor(fixture));
    assertEquals(fixture.frozen, erasureRaw(fixture));
    try (var lock = fixture.source.getConnection()) {
      lock.setAutoCommit(false);
      try (var statement =
          lock.prepareStatement("select user_id from user where user_id=? for update")) {
        statement.setString(1, ERASURE_ACCOUNT);
        try (var result = statement.executeQuery()) {
          assertTrue(result.next());
        }
      }
      coldErasureProcess("locked");
      assertEquals(ERASURE_ACCOUNT, erasureCursor(fixture));
      assertEquals(fixture.frozen, erasureRaw(fixture));
      lock.rollback();
    }
    coldErasureProcess("continue");
    assertNull(erasureCursor(fixture));
    var history = erasureRaw(fixture);
    assertEquals("FROZEN_HISTORY", history.path("phase").textValue());
    assertEquals(fixture.frozen.path("metadata"), history.path("metadata"));
    assertEquals(
        original,
        ReactivationUserSnapshotSchema.capture(
            fixture.jdbc, fixture.codec, ERASURE_ACCOUNT, false));
    var verified =
        fixture.codec.verifyReactivationHistory(
            history.path("metadata"),
            history.path("envelope").textValue(),
            "FROZEN_HISTORY",
            null,
            ERASURE_ACTOR);
    assertFalse(verified.template().path("change").has("password"));
    for (var row : poison.entrySet())
      assertTrue(
          row.getValue()
              .equals(
                  fixture.jdbc.queryForObject(
                      "select reactivation_scoped_json from user where user_id=?",
                      String.class,
                      row.getKey())));
    System.out.println(
        "Reactivation retention proof: selected<=100; coldProcesses=2; poisonRows=105; lockedRowRevisited=true; noRemoteEffects=true");
  }

  private static final String ERASURE_ACCOUNT = "85000000-0000-4000-8000-000000000071";
  private static final String ERASURE_OPERATION = "85000000-0000-4000-8000-000000000075";
  private static final String ERASURE_REALM = "85000000-0000-4000-8000-000000000074";
  private static final Instant ERASURE_NOW = Instant.parse("2026-10-25T12:00:00.123456Z");
  private static final TaskActorCredentials ERASURE_ACTOR =
      new TaskActorCredentials(
          TaskActor.ACCOUNT_ADMIN,
          "backend-account-admin",
          "synthetic-only",
          "85000000-0000-4000-8000-000000000073");
  private static final byte[] ERASURE_KEY =
      "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  private record RetentionFixture(
      DataSource source,
      JdbcTemplate jdbc,
      AccountLifecycleCodec codec,
      JsonNode original,
      JsonNode frozen) {}

  private static DataSource erasureSource() {
    String url = System.getenv("CALLER_FAIRNESS_DB_URL");
    assertEquals("jdbc:mariadb://127.0.0.1:3306/caller_fairness", url);
    return new DriverManagerDataSource(url, "root", System.getenv("CALLER_FAIRNESS_DB_PASSWORD"));
  }

  private static RetentionFixture retentionFixture() throws Exception {
    var source = erasureSource();
    var jdbc = new JdbcTemplate(source);
    var codec =
        new AccountLifecycleCodec(
            ERASURE_KEY, java.time.Clock.fixed(ERASURE_NOW, java.time.ZoneOffset.UTC));
    jdbc.execute("DROP TABLE IF EXISTS user");
    jdbc.execute("DROP TABLE IF EXISTS scheduled_task_claim");
    var user =
        new de.caritas.cob.userservice.api.model.User(
            ERASURE_ACCOUNT,
            null,
            new de.caritas.cob.userservice.api.helper.UsernameTranscoder()
                .encodeUsername("erasure-fixture"),
            "erasure@fixture.invalid",
            false);
    var born =
        java.time.LocalDateTime.ofInstant(
            ERASURE_NOW.minusSeconds(2592000), java.time.ZoneOffset.UTC);
    user.setCreateDate(born);
    user.setUpdateDate(born.plusDays(1));
    user.setTenantId(7L);
    user.setLanguageCode(com.neovisionaries.i18n.LanguageCode.en);
    user.setDeleteDate(born.plusDays(2));
    user.setDeletionReadOnlyUntil(born.plusDays(3));
    user.setDeletionLifecycleState(
        de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState
            .REACTIVATION_IN_PROGRESS);
    user.setReactivationOperationId(ERASURE_OPERATION);
    user.setReactivationOperationStartedAt(born.plusDays(4));
    var original = ReactivationUserSnapshotSchema.from(user, codec);
    var dates =
        java.util.Set.of(
            "create_date",
            "update_date",
            "delete_date",
            "deletion_read_only_until",
            "deletion_paused_until",
            "deletion_pause_created_at",
            "reactivation_operation_started_at",
            "terms_and_conditions_confirmation",
            "data_privacy_confirmation");
    var numbers = java.util.Set.of("id_old", "chat_recovery_policy_revision", "tenant_id");
    var flags =
        java.util.Set.of(
            "language_formal",
            "encourage_2fa",
            "magic_link_login_enabled",
            "notifications_enabled",
            "temporary_account");
    var columns = new java.util.ArrayList<String>();
    original
        .fieldNames()
        .forEachRemaining(
            name ->
                columns.add(
                    name
                        + " "
                        + (name.equals("user_id")
                            ? "VARCHAR(36) PRIMARY KEY"
                            : dates.contains(name)
                                ? "DATETIME(6) NULL"
                                : numbers.contains(name)
                                    ? "BIGINT NULL"
                                    : flags.contains(name) ? "BOOLEAN NULL" : "TEXT NULL")));
    jdbc.execute("CREATE TABLE user (" + String.join(",", columns) + ") ENGINE=InnoDB");
    for (String resource :
        List.of(
            "0082_scheduled_task_claim/migrate.sql",
            "20261023_identity_asker_reactivation_367/migrate.sql",
            "20261025_reactivation_erasure_cursor_367/migrate.sql")) {
      new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
              new org.springframework.core.io.ClassPathResource(
                  "db/changelog/changeset/" + resource))
          .execute(source);
    }
    var names = new java.util.ArrayList<String>();
    original.fieldNames().forEachRemaining(names::add);
    var values = new java.util.ArrayList<Object>();
    for (String name : names) {
      var value = original.get(name);
      values.add(
          value.isNull()
              ? null
              : dates.contains(name)
                  ? java.time.LocalDateTime.ofInstant(
                      Instant.parse(value.textValue()), java.time.ZoneOffset.UTC)
                  : value.isIntegralNumber()
                      ? value.longValue()
                      : value.isBoolean() ? value.booleanValue() : value.textValue());
    }
    jdbc.update(
        "insert into user("
            + String.join(",", names)
            + ") values ("
            + String.join(",", java.util.Collections.nCopies(names.size(), "?"))
            + ")",
        values.toArray());
    var origin = codec.read("{}", ObjectNode.class);
    origin.put("kind", "HUMAN_ADMIN");
    origin.put("workflowId", ERASURE_OPERATION);
    origin.put("humanSubject", "85000000-0000-4000-8000-000000000072");
    origin.put("humanIssuer", "https://identity.test/realms/history");
    origin.put("humanClient", "fixture-human");
    origin.put("humanAuthority", "PROTECTED_ACCOUNT_OPERATIONS_V1");
    var target = codec.read("{}", ObjectNode.class);
    target.put("accountId", ERASURE_ACCOUNT);
    target.put("tenantId", "7");
    target.set("origin", origin);
    var body = codec.read("{}", ObjectNode.class);
    body.set("target", target);
    body.put("operationId", ERASURE_OPERATION);
    body.put("expectedState", "a".repeat(64));
    body.putObject("change")
        .put("expectedUsername", original.path("username").textValue())
        .put("expectedEmail", original.path("email").textValue())
        .put("password", "synthetic-erasure-password");
    var metadata = codec.read("{}", ObjectNode.class);
    metadata.put("v", 1);
    metadata.put("realmId", ERASURE_REALM);
    metadata.put("operationId", ERASURE_OPERATION);
    metadata.put("command", "account.reactivate");
    metadata.put("method", "POST");
    metadata.put("route", "oriso-account-maintenance/v1/accounts/reactivate");
    metadata.put("accountId", ERASURE_ACCOUNT);
    metadata.put("tenantId", 7);
    metadata.put("ownerClient", ERASURE_ACTOR.clientId());
    metadata.put("ownerSubject", ERASURE_ACTOR.serviceSubject());
    metadata.put("originDigest", codec.digest("maintenance-origin", origin));
    metadata.put("workflowId", ERASURE_OPERATION);
    metadata.put("requestDigest", "b".repeat(64));
    metadata.put("expectedState", "a".repeat(64));
    metadata.put("authorityValidUntil", ERASURE_NOW.getEpochSecond() - 604800);
    metadata.put("representation", "FROZEN_REACTIVATION_HTTP_V1");
    var wrapper = codec.read("{}", ObjectNode.class);
    wrapper.set("body", body);
    wrapper.set("originalSql", original);
    var frozen = codec.read("{}", ObjectNode.class);
    frozen.put("v", 1);
    frozen.put("phase", "FROZEN");
    frozen.set("metadata", metadata);
    frozen.put(
        "envelope",
        codec.sealReactivationIntent(
            metadata, codec.canonical(wrapper).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    frozen.putNull("receipt");
    frozen.putNull("postSql");
    jdbc.update(
        "update user set reactivation_scoped_json=? where user_id=?",
        codec.canonical(frozen),
        ERASURE_ACCOUNT);
    assertEquals(
        original, ReactivationUserSnapshotSchema.capture(jdbc, codec, ERASURE_ACCOUNT, false));
    return new RetentionFixture(source, jdbc, codec, original, frozen);
  }

  private static ScopedAskerReactivation erasureOwner(RetentionFixture fixture, Instant now) {
    var mode =
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.port.out.IdentityClientConfig.class);
    org.mockito.Mockito.when(mode.isFiveActorMode()).thenReturn(true);
    var actors = org.mockito.Mockito.mock(TaskActorConfiguration.class);
    org.mockito.Mockito.when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE))
        .thenReturn(ERASURE_ACTOR);
    var broker =
        org.mockito.Mockito.mock(
            TaskActorTokenBroker.class,
            invocation -> {
              throw new AssertionError("local erasure reached token broker");
            });
    var http =
        new org.springframework.web.client.RestTemplate(
            (uri, method) -> {
              throw new AssertionError("local erasure reached HTTP");
            });
    var clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);
    return new ScopedAskerReactivation(
        mode,
        org.mockito.Mockito.mock(de.caritas.cob.userservice.api.helper.AuthenticatedUser.class),
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.admin.service.admin.AdminScope.class),
        org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class),
        fixture.jdbc,
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(fixture.source),
        actors,
        broker,
        new AccountLifecycleCodec(ERASURE_KEY, clock),
        http,
        clock,
        "https://identity.test",
        "https://identity.test/realms/history",
        "history",
        ERASURE_REALM,
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.admin.service.IdentityReactivationSagaStore.class,
            invocation -> {
              throw new AssertionError("local erasure reached saga callback");
            }));
  }

  private static ScheduledTaskClaimService.ClaimLease retentionLease(
      RetentionFixture fixture, Instant now) {
    var tx =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(fixture.source));
    return tx.execute(
        ignored -> {
          var rows =
              fixture.jdbc.queryForList(
                  "select claimed_until from scheduled_task_claim where task_name=? for update",
                  ScopedAskerReactivation.SECRET_ERASURE_TASK);
          var until =
              java.time.LocalDateTime.ofInstant(now.plusSeconds(300), java.time.ZoneOffset.UTC);
          if (rows.isEmpty())
            fixture.jdbc.update(
                "insert into scheduled_task_claim(task_name,claimed_at,claimed_until) values (?,?,?)",
                ScopedAskerReactivation.SECRET_ERASURE_TASK,
                java.time.LocalDateTime.ofInstant(now, java.time.ZoneOffset.UTC),
                until);
          else {
            assertFalse(
                ((Timestamp) rows.getFirst().get("claimed_until")).toInstant().isAfter(now));
            fixture.jdbc.update(
                "update scheduled_task_claim set claimed_at=?,claimed_until=? where task_name=?",
                java.time.LocalDateTime.ofInstant(now, java.time.ZoneOffset.UTC),
                until,
                ScopedAskerReactivation.SECRET_ERASURE_TASK);
          }
          return new ScheduledTaskClaimService.ClaimLease(
              ScopedAskerReactivation.SECRET_ERASURE_TASK, until);
        });
  }

  private static JsonNode erasureRaw(RetentionFixture fixture) {
    return fixture.codec.read(
        fixture.jdbc.queryForObject(
            "select reactivation_scoped_json from user where user_id=?",
            String.class,
            ERASURE_ACCOUNT),
        JsonNode.class);
  }

  private static String erasureCursor(RetentionFixture fixture) {
    return fixture.jdbc.queryForObject(
        "select reactivation_erasure_after_account_id from scheduled_task_claim where task_name=?",
        String.class,
        ScopedAskerReactivation.SECRET_ERASURE_TASK);
  }

  private static void coldErasureProcess(String stage) throws Exception {
    assertTrue(java.util.Set.of("locked", "continue").contains(stage));
    assertTrue(Runtime.getRuntime().maxMemory() <= 512L * 1024 * 1024);
    assertTrue(Runtime.getRuntime().availableProcessors() <= 2);
    var os =
        (com.sun.management.OperatingSystemMXBean)
            java.lang.management.ManagementFactory.getOperatingSystemMXBean();
    assertTrue(
        os.getFreeMemorySize() > 384L * 1024 * 1024,
        "cold process requires actual memory headroom");
    var limit = java.nio.file.Path.of("/sys/fs/cgroup/memory.max");
    if (java.nio.file.Files.isRegularFile(limit)) {
      String value = java.nio.file.Files.readString(limit).trim();
      if (!"max".equals(value))
        assertTrue(
            Long.parseLong(value)
                    - Long.parseLong(
                        java.nio.file.Files.readString(
                                java.nio.file.Path.of("/sys/fs/cgroup/memory.current"))
                            .trim())
                > 384L * 1024 * 1024,
            "cold process requires cgroup headroom");
    }
    String classpath = System.getProperty("surefire.test.class.path");
    assertNotNull(classpath);
    var executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java");
    assertTrue(java.nio.file.Files.isExecutable(executable));
    var builder =
        new ProcessBuilder(
            executable.toString(),
            "-Xmx192m",
            "-XX:MaxMetaspaceSize=128m",
            "-XX:ActiveProcessorCount=1",
            "-cp",
            classpath,
            AccountLifecycleRetryFairnessMariaDbIT.class.getName(),
            stage);
    builder.environment().clear();
    for (String name :
        List.of("CALLER_FAIRNESS_DB_URL", "CALLER_FAIRNESS_DB_PASSWORD", "PATH", "TZ", "LANG"))
      if (System.getenv(name) != null) builder.environment().put(name, System.getenv(name));
    var output = java.nio.file.Files.createTempFile("reactivation-erasure-child-", ".txt");
    builder.redirectOutput(output.toFile());
    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    Process child = null;
    try {
      child = builder.start();
      assertTrue(
          child.waitFor(20, java.util.concurrent.TimeUnit.SECONDS),
          "bounded cold process timed out");
      assertEquals(0, child.exitValue(), "bounded cold process failed");
      assertEquals("REACTIVATION_ERASURE_CHILD_OK\n", java.nio.file.Files.readString(output));
    } finally {
      if (child != null && child.isAlive()) {
        child.destroyForcibly();
        child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
      }
      java.nio.file.Files.deleteIfExists(output);
    }
  }

  /** Only the two fixed test stages run here; no human request, network command or scheduler. */
  public static void main(String[] args) {
    var report = System.out;
    System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
    System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
    try {
      if (args.length != 1 || !java.util.Set.of("locked", "continue").contains(args[0]))
        throw new AssertionError("fixed child stage required");
      assertTrue(
          java.lang.management.ManagementFactory.getRuntimeMXBean()
              .getInputArguments()
              .containsAll(
                  List.of("-Xmx192m", "-XX:MaxMetaspaceSize=128m", "-XX:ActiveProcessorCount=1")));
      assertTrue(Runtime.getRuntime().maxMemory() <= 192L * 1024 * 1024);
      assertEquals(1, Runtime.getRuntime().availableProcessors());
      var source = erasureSource();
      var jdbc = new JdbcTemplate(source);
      var codec =
          new AccountLifecycleCodec(
              ERASURE_KEY,
              java.time.Clock.fixed(ERASURE_NOW.plusSeconds(602), java.time.ZoneOffset.UTC));
      var fixture = new RetentionFixture(source, jdbc, codec, null, null);
      if ("locked".equals(args[0])) {
        assertEquals("10000000-0000-4000-8000-000000000099", erasureCursor(fixture));
        var lease = retentionLease(fixture, ERASURE_NOW.plusSeconds(301));
        assertEquals(
            0,
            erasureOwner(fixture, ERASURE_NOW.plusSeconds(301)).eraseExpiredScopedSecrets(lease));
        assertEquals(ERASURE_ACCOUNT, erasureCursor(fixture));
        assertEquals("FROZEN", erasureRaw(fixture).path("phase").textValue());
      } else {
        assertEquals(ERASURE_ACCOUNT, erasureCursor(fixture));
        var lease = retentionLease(fixture, ERASURE_NOW.plusSeconds(602));
        var owner = erasureOwner(fixture, ERASURE_NOW.plusSeconds(602));
        assertEquals(0, owner.eraseExpiredScopedSecrets(lease));
        assertNull(erasureCursor(fixture));
        assertEquals(0, owner.eraseExpiredScopedSecrets(lease));
        assertEquals("10000000-0000-4000-8000-000000000099", erasureCursor(fixture));
        assertEquals(1, owner.eraseExpiredScopedSecrets(lease));
        assertEquals(ERASURE_ACCOUNT, erasureCursor(fixture));
        assertEquals(0, owner.eraseExpiredScopedSecrets(lease));
        assertNull(erasureCursor(fixture));
        assertEquals("FROZEN_HISTORY", erasureRaw(fixture).path("phase").textValue());
      }
      report.println("REACTIVATION_ERASURE_CHILD_OK");
    } catch (Throwable failure) {
      report.println(
          "REACTIVATION_ERASURE_CHILD_FAILED_"
              + failure.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_$]", ""));
      System.exit(1);
    }
  }
}
