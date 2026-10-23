package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import de.caritas.cob.userservice.api.workflow.delete.service.ScheduledAskerMaintenance.AcknowledgedDeletion;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimWriter;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/** Actual JPA/lease/private owner issuance. The signed HTTP fixture is not native acceptance. */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  JpaAuditingConfiguration.class,
  ScheduledTaskClaimWriter.class,
  ScheduledTaskClaimService.class,
  ScheduledDeletionContinuationJpaTest.TimeConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScheduledDeletionContinuationJpaTest {
  private static final String ACCOUNT = "11111111-1111-4111-8111-111111111111";
  private static final String REALM = "22222222-2222-4222-8222-222222222222";
  private static final String SUBJECT = "33333333-3333-4333-8333-333333333333";
  private static final String ENDPOINT =
      "https://identity.test/realms/fixture/oriso-account-maintenance/v1/accounts/" + ACCOUNT;
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private static final String STATE = "a".repeat(64);
  private static final Set<String> USER_FIELDS =
      Set.of(
          "user_id",
          "id_old",
          "username",
          "email",
          "matrix_user_id",
          "chat_recovery_mode",
          "chat_recovery_policy_revision",
          "language_formal",
          "delete_date",
          "deletion_lifecycle_state",
          "deletion_read_only_until",
          "deletion_paused_until",
          "deletion_pause_reason",
          "deletion_paused_by",
          "deletion_pause_created_at",
          "reactivation_operation_id",
          "reactivation_operation_started_at",
          "tenant_id",
          "create_date",
          "update_date",
          "encourage_2fa",
          "magic_link_login_enabled",
          "language_code",
          "terms_and_conditions_confirmation",
          "data_privacy_confirmation",
          "notifications_enabled",
          "notifications_settings",
          "temporary_account",
          "avatar_id");
  @Autowired private DataSource dataSource;
  @Autowired private UserRepository users;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private EntityManager entities;
  @Autowired private ScheduledTaskClaimService claims;
  @Autowired private Clock clock;
  private JdbcTemplate jdbc;
  private AccountLifecycleCodec codec;
  private TaskActorCredentials actor;
  private TaskActorConfiguration actors;
  private TaskActorTokenBroker broker;
  private RestTemplate http;
  private MockRestServiceServer server;
  private final AtomicInteger nativeReads = new AtomicInteger();
  private final AtomicInteger nativeEffects = new AtomicInteger();
  private final AtomicInteger nativeCalls = new AtomicInteger();
  private final AtomicInteger callbacks = new AtomicInteger();
  private String savedBody;
  private String savedReply;
  private boolean loseReply;
  private boolean loseReadReply;
  private boolean forgeReply;

  @BeforeEach
  void actualJpaAndLeaseWithCompleteUserSchema() {
    assertInstanceOf(JpaTransactionManager.class, transactions);
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    TenantContext.clear();
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("drop table if exists identity_maintenance_intent");
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "db/changelog/changeset/20261021_identity_maintenance_367/migrate.sql"),
            new ClassPathResource(
                "db/changelog/changeset/20261022_identity_maintenance_retry_367/migrate.sql"))
        .execute(dataSource);
    jdbc.update("delete from scheduled_task_claim where task_name='account-deletion'");
    users.findById(ACCOUNT).ifPresent(users::delete);
    var user =
        new User(
            ACCOUNT,
            null,
            new UsernameTranscoder().encodeUsername("owned-cleanup"),
            "ordinary@fixture.invalid",
            false);
    user.setTenantId(7L);
    user.setLanguageCode(LanguageCode.en);
    user.setEncourage2fa(false);
    user.setMatrixUserId("@owned-cleanup:fixture.invalid");
    var now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    user.setDeleteDate(now.minusDays(3));
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(now.minusDays(1));
    users.save(user);
    assertNotNull(
        jdbc.queryForObject(
            "select create_date from user where user_id=?", java.sql.Timestamp.class, ACCOUNT));
    codec = new AccountLifecycleCodec(KEY);
    actor =
        new TaskActorCredentials(
            TaskActor.ACCOUNT_ADMIN, "backend-account-admin", "fixture-secret", SUBJECT);
    actors = mock(TaskActorConfiguration.class);
    when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(actor);
    broker = mock(TaskActorTokenBroker.class);
    var checked = mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
    when(checked.accessToken()).thenReturn("fixture-account-admin");
    when(checked.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);
    when(checked.purpose()).thenReturn(TaskPurpose.ACCOUNT_MAINTENANCE);
    when(checked.clientId()).thenReturn(actor.clientId());
    when(checked.subject()).thenReturn(SUBJECT);
    when(checked.issuer()).thenReturn("https://identity.test/realms/fixture");
    when(checked.validatedJwtIssuedAt()).thenReturn(Instant.now().minusSeconds(1).getEpochSecond());
    when(checked.validatedJwtExpiresAt())
        .thenReturn(Instant.now().plusSeconds(60).getEpochSecond());
    when(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(checked);
    http = new RestTemplate();
    server = MockRestServiceServer.bindTo(http).build();
    server
        .expect(
            manyTimes(),
            outbound ->
                assertTrue(
                    Set.of(ENDPOINT + "/read", ENDPOINT + "/delete")
                        .contains(outbound.getURI().toString())))
        .andRespond(
            outbound -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals(HttpMethod.POST, outbound.getMethod());
              assertEquals(
                  "Bearer fixture-account-admin", outbound.getHeaders().getFirst("Authorization"));
              String command = outbound.getURI().getPath().endsWith("/read") ? "read" : "delete";
              String raw = ((MockClientHttpRequest) outbound).getBodyAsString();
              var body = codec.read(raw, JsonNode.class);
              verifyCapturedProof(
                  outbound.getHeaders().getFirst("X-ORISO-Task-Context"), command, body);
              if ("read".equals(command)) {
                nativeReads.incrementAndGet();
                if (loseReadReply) {
                  loseReadReply = false;
                  throw new ResourceAccessException("fixture loses first native read reply");
                }
                return withSuccess(projection(), MediaType.APPLICATION_JSON)
                    .createResponse(outbound);
              }
              nativeCalls.incrementAndGet();
              if (savedBody == null) {
                savedBody = raw;
                savedReply = receipt(body);
                nativeEffects.incrementAndGet();
              } else
                assertEquals(savedBody, raw, "An uncertain native command cannot be regenerated");
              if (loseReply) {
                loseReply = false;
                throw new ResourceAccessException("fixture lost native reply");
              }
              String reply = savedReply;
              if (forgeReply) {
                var forged =
                    codec.read(reply, com.fasterxml.jackson.databind.node.ObjectNode.class);
                String value = forged.path("receipt").textValue();
                forged.put("receipt", value.substring(0, value.indexOf('.') + 1) + "0".repeat(64));
                reply = codec.canonical(forged);
              }
              return withSuccess(reply, MediaType.APPLICATION_JSON).createResponse(outbound);
            });
  }

  @Test
  void acknowledgedNativeDeletionIssuesOneCurrentJpaOwnedPrivateFrame() {
    owner(true, this::inspectActualPrivateFrame).performDeletionWorkflow();
    assertEquals("ACKNOWLEDGED", status());
    assertEquals(1, nativeEffects.get());
    assertEquals(1, nativeReads.get());
    assertEquals(1, callbacks.get(), "Durable native ACK must reach the owned continuation");
    var envelope =
        codec.read(
            jdbc.queryForObject(
                "select frozen_body from identity_maintenance_intent where subject_id=?",
                String.class,
                ACCOUNT),
            JsonNode.class);
    assertEquals(
        Set.of("representation", "originalSql", "body", "bindingDigest"), fields(envelope));
    assertEquals("CLAIMED_SCHEDULED_ASKER_DELETE_V1", envelope.path("representation").textValue());
    assertEquals(USER_FIELDS, fields(envelope.path("originalSql")));
    assertEquals(ACCOUNT, envelope.path("originalSql").path("user_id").textValue());
    assertEquals(
        jdbc.queryForObject(
                "select create_date from user where user_id=?", java.sql.Timestamp.class, ACCOUNT)
            .toLocalDateTime()
            .toInstant(ZoneOffset.UTC)
            .toString(),
        envelope.path("originalSql").path("create_date").textValue());
    assertEquals(
        "@owned-cleanup:fixture.invalid",
        envelope.path("originalSql").path("matrix_user_id").textValue());
    assertEquals(
        Set.of("target", "operationId", "expectedState", "change"), fields(envelope.path("body")));
    assertTrue(envelope.path("bindingDigest").textValue().matches("[0-9a-f]{64}"));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from scheduled_task_claim where task_name='account-deletion'",
            Integer.class));
    server.verify();
  }

  @Test
  void savedAckColdRestartIssuesNewHeldFrameWithoutNativeReinvoke() {
    owner(
            true,
            frame -> {
              throw new IllegalStateException("fixture retains cleanup for restart");
            })
        .performDeletionWorkflow();
    assertEquals("ACKNOWLEDGED", status());
    String originalBody = savedBody;
    owner(true, this::inspectActualPrivateFrame).performDeletionWorkflow();
    assertEquals(1, callbacks.get(), "Saved ACK must resume cleanup under a freshly held lease");
    assertEquals(1, nativeCalls.get(), "Acknowledged native deletion must never be invoked again");
    assertEquals(1, nativeEffects.get());
    assertEquals(originalBody, savedBody);
    assertTrue(users.findById(ACCOUNT).isPresent());
    server.verify();
  }

  @Test
  void lostNativeReplyCannotIssueFrameBeforeExactSavedAck() {
    loseReply = true;
    owner(true, this::inspectActualPrivateFrame).performDeletionWorkflow();
    assertEquals(0, callbacks.get());
    assertEquals("FROZEN", status());
    assertEquals(1, nativeEffects.get());
    owner(true, this::inspectActualPrivateFrame).performDeletionWorkflow();
    assertEquals("ACKNOWLEDGED", status());
    assertEquals(1, callbacks.get());
    assertEquals(1, nativeEffects.get());
    assertEquals(2, nativeCalls.get());
    server.verify();
  }

  @Test
  void forgedNativeReceiptNeverIssuesFrameOrDownstreamAuthority() {
    forgeReply = true;
    owner(true, this::inspectActualPrivateFrame).performDeletionWorkflow();
    assertEquals(0, callbacks.get());
    assertEquals("FROZEN", status());
    assertTrue(users.findById(ACCOUNT).isPresent());
    server.verify();
  }

  @Test
  void missingJpaCleanupOwnerRefusesBeforeAnyCleanupCallback() {
    owner(false, this::inspectActualPrivateFrame).performDeletionWorkflow();
    assertEquals(0, callbacks.get());
    assertEquals(0, nativeReads.get());
    assertEquals(0, nativeCalls.get());
    assertEquals(0, nativeEffects.get());
    assertTrue(users.findById(ACCOUNT).isPresent());
  }

  @Test
  void missingAcknowledgedCleanupConsumerRefusesBeforeNativeEffects() {
    owner(true, null).performDeletionWorkflow();
    assertEquals(0, callbacks.get());
    assertEquals(0, nativeReads.get());
    assertEquals(0, nativeCalls.get());
    assertEquals(0, nativeEffects.get());
    assertTrue(users.findById(ACCOUNT).isPresent());
  }

  @Test
  void replacedSqlIncarnationCannotAdoptSavedNativeAcknowledgement() {
    owner(
            true,
            frame -> {
              throw new IllegalStateException("fixture retains original user");
            })
        .performDeletionWorkflow();
    assertEquals("ACKNOWLEDGED", status());
    jdbc.update(
        "update user set create_date=dateadd('DAY',1,create_date),username=? where user_id=?",
        new UsernameTranscoder().encodeUsername("replacement-cleanup"),
        ACCOUNT);
    owner(true, this::inspectActualPrivateFrame).performDeletionWorkflow();
    assertEquals(0, callbacks.get());
    assertEquals(1, nativeCalls.get());
    assertTrue(users.findById(ACCOUNT).isPresent());
    server.verify();
  }

  @Test
  void actualJpaFlushAndFinalCommitMarkAppliedWithoutErasingOriginalNativeProof() {
    owner(
            true,
            frame -> {
              inspectActualPrivateFrame(frame);
              assertFalse(frame.completed());
              assertTrue(frame.completeOwnedLocalDeletion(() -> users.deleteById(ACCOUNT)));
              assertTrue(frame.completed());
              frame.requireCurrent();
            })
        .performDeletionWorkflow();
    assertEquals(1, callbacks.get());
    assertEquals("APPLIED", status());
    assertTrue(users.findById(ACCOUNT).isEmpty());
    assertNotNull(
        jdbc.queryForObject(
            "select receipt_json from identity_maintenance_intent where subject_id=?",
            String.class,
            ACCOUNT));
    assertNotNull(
        jdbc.queryForObject(
            "select frozen_body from identity_maintenance_intent where subject_id=?",
            String.class,
            ACCOUNT));
    assertEquals(1, nativeEffects.get());
  }

  @Test
  void lostFinalJpaCommitReplyRecoversAppliedBeforeColdRestartCanRepeatCleanup() {
    var uncertain =
        new LostCommitReplyJpaManager(
            ((JpaTransactionManager) transactions).getEntityManagerFactory(), dataSource);
    owner(
            true,
            frame -> {
              inspectActualPrivateFrame(frame);
              try {
                assertTrue(
                    frame.completeOwnedLocalDeletion(
                        () -> {
                          users.deleteById(ACCOUNT);
                          uncertain.loseNextCommit = true;
                        }));
              } catch (RuntimeException lostReply) {
                assertTrue(
                    frame.completed(),
                    "Only fresh APPLIED/absence proof resolves lost commit reply");
              }
              assertTrue(frame.completed());
            },
            uncertain)
        .performDeletionWorkflow();
    assertEquals(1, callbacks.get());
    assertEquals("APPLIED", status());
    assertTrue(users.findById(ACCOUNT).isEmpty());
    owner(true, frame -> fail("An applied absent account cannot repeat downstream cleanup"))
        .performDeletionWorkflow();
    assertEquals(1, nativeCalls.get());
    assertEquals(1, nativeEffects.get());
  }

  @Test
  void retainedExactOriginalUserKeepsAcknowledgedRetryableInsteadOfApplied() {
    owner(
            true,
            frame -> {
              inspectActualPrivateFrame(frame);
              assertFalse(frame.completeOwnedLocalDeletion(() -> {}));
              assertFalse(frame.completed());
              frame.requireCurrent();
            })
        .performDeletionWorkflow();
    assertEquals(1, callbacks.get());
    assertEquals("ACKNOWLEDGED", status());
    assertTrue(users.findById(ACCOUNT).isPresent());
    owner(
            true,
            frame -> {
              inspectActualPrivateFrame(frame);
              assertTrue(frame.completeOwnedLocalDeletion(() -> users.deleteById(ACCOUNT)));
            })
        .performDeletionWorkflow();
    assertEquals(2, callbacks.get());
    assertEquals("APPLIED", status());
    assertEquals(1, nativeCalls.get());
  }

  @Test
  void fakeAppliedStatusCannotAuthorizeCleanupWhileOriginalUserStillExists() {
    owner(
            true,
            frame -> {
              throw new IllegalStateException("fixture retains original");
            })
        .performDeletionWorkflow();
    assertEquals("ACKNOWLEDGED", status());
    jdbc.update(
        "update identity_maintenance_intent set status='APPLIED' where subject_id=?", ACCOUNT);
    owner(true, this::inspectActualPrivateFrame).performDeletionWorkflow();
    assertEquals(0, callbacks.get());
    assertTrue(users.findById(ACCOUNT).isPresent());
    assertEquals(1, nativeCalls.get());
  }

  @Test
  void appliedTombstoneCannotAdoptARecreatedSqlAccountWithTheSameId() {
    var deletionStarted =
        jdbc.queryForObject(
                "select delete_date from user where user_id=?", java.sql.Timestamp.class, ACCOUNT)
            .toLocalDateTime();
    owner(
            true,
            frame -> {
              inspectActualPrivateFrame(frame);
              assertTrue(frame.completeOwnedLocalDeletion(() -> users.deleteById(ACCOUNT)));
            })
        .performDeletionWorkflow();
    assertEquals("APPLIED", status());
    var replacement =
        new User(
            ACCOUNT,
            null,
            new UsernameTranscoder().encodeUsername("replacement-cleanup"),
            "replacement@fixture.invalid",
            false);
    replacement.setTenantId(7L);
    replacement.setDeleteDate(deletionStarted);
    replacement.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    replacement.setDeletionReadOnlyUntil(deletionStarted.minusDays(1));
    replacement.setLanguageCode(LanguageCode.en);
    replacement.setEncourage2fa(false);
    replacement.setMatrixUserId("@replacement-cleanup:fixture.invalid");
    users.save(replacement);
    owner(true, frame -> fail("Saved original deletion cannot authorize a recreated User"))
        .performDeletionWorkflow();
    assertTrue(users.findById(ACCOUNT).isPresent());
    assertEquals("replacement@fixture.invalid", users.findById(ACCOUNT).orElseThrow().getEmail());
    assertEquals(1, nativeCalls.get());
  }

  @Test
  void changedLeaseVersionStopsTheNextOwnedCallbackAndCannotClearTheNewLease() {
    var localEffects = new AtomicInteger();
    owner(
            true,
            frame -> {
              inspectActualPrivateFrame(frame);
              jdbc.update(
                  "update scheduled_task_claim set claimed_until=dateadd('SECOND',1,claimed_until) where task_name='account-deletion'");
              assertThrows(
                  RuntimeException.class, () -> frame.runOwnedLocal(localEffects::incrementAndGet));
            })
        .performDeletionWorkflow();
    assertEquals(1, callbacks.get());
    assertEquals(0, localEffects.get());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from scheduled_task_claim where task_name='account-deletion'",
            Integer.class));
    assertTrue(users.findById(ACCOUNT).isPresent());
  }

  @Test
  void callerSwappedUserMatrixOrUsernameCannotPassOriginalProjectionComparison() {
    owner(
            true,
            frame -> {
              inspectActualPrivateFrame(frame);
              var projected = new AtomicReference<User>();
              frame.runOwnedLocal(() -> projected.set(users.findById(ACCOUNT).orElseThrow()));
              projected.get().setMatrixUserId("@foreign:fixture.invalid");
              assertThrows(RuntimeException.class, () -> frame.requireCurrentUser(projected.get()));
              projected.get().setMatrixUserId("@owned-cleanup:fixture.invalid");
              projected
                  .get()
                  .setUsername(new UsernameTranscoder().encodeUsername("foreign-cleanup"));
              assertThrows(RuntimeException.class, () -> frame.requireCurrentUser(projected.get()));
            })
        .performDeletionWorkflow();
    assertEquals(1, callbacks.get());
    assertEquals(
        "@owned-cleanup:fixture.invalid", users.findById(ACCOUNT).orElseThrow().getMatrixUserId());
  }

  @Test
  void changedActualSqlSnapshotStopsTheNextOwnedCallbackWithinTheSameFrame() {
    var localEffects = new AtomicInteger();
    owner(
            true,
            frame -> {
              inspectActualPrivateFrame(frame);
              jdbc.update(
                  "update user set matrix_user_id=? where user_id=?",
                  "@foreign:fixture.invalid",
                  ACCOUNT);
              assertThrows(
                  RuntimeException.class, () -> frame.runOwnedLocal(localEffects::incrementAndGet));
            })
        .performDeletionWorkflow();
    assertEquals(1, callbacks.get());
    assertEquals(0, localEffects.get());
    assertEquals("ACKNOWLEDGED", status());
    assertEquals(1, nativeCalls.get());
  }

  @Test
  void firstReadLossDurablyRetainsOriginalUserBeforeAnyNativeSnapshotAndColdRestartReusesIt() {
    loseReadReply = true;
    owner(true, frame -> fail("Unknown native read cannot issue cleanup")).performDeletionWorkflow();
    assertEquals("PREPARED", status());
    assertEquals(1, nativeReads.get());
    assertEquals(0, nativeCalls.get());
    assertEquals(0, callbacks.get());
    String operation = intentValue("operation_id"), before = intentValue("frozen_body");
    assertNotNull(before, "The original SQL incarnation must commit before the first native read");
    var prepared = codec.read(before, com.fasterxml.jackson.databind.node.ObjectNode.class);
    assertEquals(Set.of("representation", "originalSql", "body", "bindingDigest"), fields(prepared));
    assertTrue(prepared.path("body").isNull());
    assertEquals(USER_FIELDS, fields(prepared.path("originalSql")));
    assertEquals("HARD_DELETE_IN_PROGRESS", prepared.path("originalSql").path("deletion_lifecycle_state").textValue());
    String binding = prepared.remove("bindingDigest").textValue();
    assertEquals(HexFormat.of().formatHex(hmac("claimed-scheduled-deletion-intent/v1\n" + codec.canonical(prepared))), binding);
    var original = prepared.path("originalSql").deepCopy();
    owner(true, frame -> {
      inspectActualPrivateFrame(frame);
      assertTrue(frame.completeOwnedLocalDeletion(() -> users.deleteById(ACCOUNT)));
    }).performDeletionWorkflow();
    assertEquals("APPLIED", status());
    assertEquals(operation, intentValue("operation_id"));
    assertEquals(original, codec.read(intentValue("frozen_body"), JsonNode.class).path("originalSql"));
    assertEquals(2, nativeReads.get());
    assertEquals(1, nativeEffects.get());
    assertEquals(1, callbacks.get());
  }

  @Test
  void lostFirstReadNeverRecapturesARecreatedUserWithSameUuidAndDeletionDate() {
    loseReadReply = true;
    owner(true, frame -> fail("Unknown native read cannot issue cleanup")).performDeletionWorkflow();
    assertEquals("PREPARED", status());
    assertEquals(1, nativeReads.get());
    String operation = intentValue("operation_id"), envelope = intentValue("frozen_body");
    var original = users.findById(ACCOUNT).orElseThrow();
    var deletionStarted = original.getDeleteDate();
    var originalBirth = original.getCreateDate();
    users.delete(original);
    var replacement = new User(ACCOUNT, null, new UsernameTranscoder().encodeUsername("owned-cleanup"), "replacement@fixture.invalid", false);
    replacement.setTenantId(7L);
    replacement.setDeleteDate(deletionStarted);
    replacement.setCreateDate(originalBirth.plusDays(1));
    replacement.setDeletionLifecycleState(DeletionLifecycleState.HARD_DELETE_IN_PROGRESS);
    replacement.setDeletionReadOnlyUntil(deletionStarted.minusDays(1));
    replacement.setLanguageCode(LanguageCode.en);
    replacement.setEncourage2fa(false);
    replacement.setMatrixUserId("@replacement:fixture.invalid");
    users.save(replacement);
    owner(true, frame -> fail("A recreated SQL incarnation cannot adopt an unknown first read")).performDeletionWorkflow();
    assertEquals(1, nativeReads.get(), "Refuse before ANY second native read, not after remote effects");
    assertEquals(0, nativeCalls.get());
    assertEquals(0, nativeEffects.get());
    assertEquals(0, callbacks.get());
    assertEquals(operation, intentValue("operation_id"));
    assertEquals(envelope, intentValue("frozen_body"));
    assertEquals("replacement@fixture.invalid", users.findById(ACCOUNT).orElseThrow().getEmail());
  }

  @Test
  void historicalNullPreparedSourceStaysHeldInsteadOfBackfillingFromCurrentUser() {
    loseReadReply = true;
    owner(true, frame -> fail("Unknown native read cannot issue cleanup")).performDeletionWorkflow();
    assertEquals("PREPARED", status());
    assertEquals(1, nativeReads.get());
    String operation = intentValue("operation_id");
    jdbc.update("update identity_maintenance_intent set frozen_body=null where operation_id=?", operation);
    owner(true, frame -> fail("Historical absent custody cannot issue cleanup")).performDeletionWorkflow();
    assertEquals(1, nativeReads.get());
    assertEquals(0, nativeEffects.get());
    assertEquals(0, callbacks.get());
    assertEquals("PREPARED", status());
    assertNull(intentValue("frozen_body"));
    assertEquals(operation, intentValue("operation_id"));
  }

  private String intentValue(String column) {
    return jdbc.queryForObject("select " + column + " from identity_maintenance_intent where subject_id=?", String.class, ACCOUNT);
  }

  private ScheduledAskerMaintenance owner(
      boolean configureJpa,
      Consumer<AcknowledgedDeletion> cleanup,
      PlatformTransactionManager manager) {
    var owner =
        new ScheduledAskerMaintenance(
            jdbc,
            manager,
            claims,
            clock,
            new DeletionLifecycleService(),
            actors,
            broker,
            codec,
            http,
            "https://identity.test",
            "fixture",
            REALM);
    if (configureJpa) owner.configureJpaCleanupOwner(entities);
    if (cleanup != null) owner.configureAcknowledgedCleanup(cleanup);
    return owner;
  }

  private static final class LostCommitReplyJpaManager extends JpaTransactionManager {
    private boolean loseNextCommit;

    private LostCommitReplyJpaManager(
        jakarta.persistence.EntityManagerFactory factory, DataSource source) {
      super(Objects.requireNonNull(factory));
      setDataSource(source);
    }

    @Override
    protected void doCommit(
        org.springframework.transaction.support.DefaultTransactionStatus status) {
      super.doCommit(status);
      if (loseNextCommit) {
        loseNextCommit = false;
        throw new IllegalStateException("fixture loses only the reply after actual JPA commit");
      }
    }
  }

  private ScheduledAskerMaintenance owner(
      boolean configureJpa, Consumer<AcknowledgedDeletion> cleanup) {
    return owner(configureJpa, cleanup, transactions);
  }

  private void inspectActualPrivateFrame(AcknowledgedDeletion frame) {
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    frame.requireCurrent();
    assertEquals(ACCOUNT, frame.accountId());
    assertEquals(7L, frame.tenantId());
    assertTrue(frame.hasLocalUser());
    assertFalse(frame.inactivityCause());
    var actual = new AtomicReference<User>();
    frame.runOwnedLocal(
        () -> {
          assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
          actual.set(users.findById(ACCOUNT).orElseThrow());
          assertTrue(entities.isJoinedToTransaction());
        });
    frame.requireCurrentUser(actual.get());
    callbacks.incrementAndGet();
  }

  private String status() {
    return jdbc.queryForObject(
        "select status from identity_maintenance_intent where subject_id=?", String.class, ACCOUNT);
  }

  private static Set<String> fields(JsonNode value) {
    var result = new HashSet<String>();
    value.fieldNames().forEachRemaining(result::add);
    return result;
  }

  private String projection() {
    var row = new TreeMap<String, Object>();
    row.put("id", ACCOUNT);
    row.put("username", "owned-cleanup");
    row.put("email", "ordinary@fixture.invalid");
    row.put("firstName", "Owned");
    row.put("lastName", "Cleanup");
    row.put("enabled", false);
    row.put("emailVerified", true);
    row.put("tenantId", "7");
    row.put("preferredLanguage", null);
    row.put("effectiveRoles", List.of("user"));
    row.put("passwordChangeRequired", false);
    row.put("state", STATE);
    return codec.canonical(row);
  }

  private void verifyCapturedProof(String proof, String command, JsonNode body) {
    assertNotNull(proof);
    String[] parts = proof.split("\\.", -1);
    assertEquals(2, parts.length);
    var decoder = Base64.getUrlDecoder();
    String raw = new String(decoder.decode(parts[0]), StandardCharsets.UTF_8);
    var claims = codec.read(raw, JsonNode.class);
    assertEquals(codec.canonical(claims), raw);
    assertEquals(
        Set.of(
            "iss",
            "aud",
            "purpose",
            "operation",
            "method",
            "actor",
            "realm",
            "target",
            "taskClient",
            "taskSubject",
            "payloadDigest",
            "jti",
            "nonce",
            "iat",
            "exp"),
        fields(claims));
    assertEquals("userservice", claims.path("iss").textValue());
    assertEquals("oriso-account-maintenance", claims.path("aud").textValue());
    assertEquals("ACCOUNT_MAINTENANCE", claims.path("purpose").textValue());
    assertEquals("account." + command, claims.path("operation").textValue());
    assertEquals("POST", claims.path("method").textValue());
    assertEquals("ACCOUNT_ADMIN", claims.path("actor").textValue());
    assertEquals("fixture", claims.path("realm").textValue());
    assertEquals("realm/" + REALM + "/account/" + ACCOUNT, claims.path("target").textValue());
    assertEquals("backend-account-admin", claims.path("taskClient").textValue());
    assertEquals(SUBJECT, claims.path("taskSubject").textValue());
    assertEquals(codec.digest("payload", body), claims.path("payloadDigest").textValue());
    assertEquals(
        UUID.fromString(claims.path("jti").textValue()).toString(), claims.path("jti").textValue());
    assertTrue(claims.path("nonce").textValue().matches("[0-9a-f]{32}"));
    long issued = claims.path("iat").longValue();
    assertTrue(issued > 0 && issued <= clock.instant().getEpochSecond());
    assertEquals(issued + 60, claims.path("exp").longValue());
    assertTrue(claims.path("exp").longValue() > clock.instant().getEpochSecond());
    assertEquals(
        Base64.getUrlEncoder().withoutPadding().encodeToString(hmac("proof\n" + parts[0])),
        parts[1]);
  }

  private static byte[] hmac(String input) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
      return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.GeneralSecurityException impossibleFixtureFailure) {
      throw new AssertionError("Fixture MAC unavailable", impossibleFixtureFailure);
    }
  }

  private String receipt(JsonNode body) {
    var outcome = new TreeMap<String, Object>();
    outcome.put("realmId", REALM);
    outcome.put("accountId", ACCOUNT);
    outcome.put("operationId", body.path("operationId").textValue());
    outcome.put("command", "delete");
    outcome.put("ownerClient", actor.clientId());
    outcome.put("ownerSubject", SUBJECT);
    outcome.put("intentDigest", codec.digest("maintenance-intent", body));
    outcome.put("previousState", body.path("expectedState").textValue());
    outcome.put("tenantId", "7");
    outcome.put(
        "originDigest", codec.digest("maintenance-origin", body.path("target").path("origin")));
    outcome.put("result", "DELETED");
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(codec.canonical(outcome).getBytes(StandardCharsets.UTF_8));
    String signed;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
      signed =
          encoded
              + "."
              + HexFormat.of()
                  .formatHex(
                      mac.doFinal(
                          ("maintenance-operation-receipt\n" + encoded)
                              .getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException impossibleFixtureFailure) {
      throw new AssertionError("Fixture receipt unavailable", impossibleFixtureFailure);
    }
    return codec.canonical(
        Map.of(
            "accountId",
            ACCOUNT,
            "operationId",
            body.path("operationId").textValue(),
            "result",
            "DELETED",
            "receipt",
            signed));
  }

  @TestConfiguration
  static class TimeConfiguration {
    @Bean
    Clock ownedDeletionClock() {
      return Clock.systemUTC();
    }
  }
}
