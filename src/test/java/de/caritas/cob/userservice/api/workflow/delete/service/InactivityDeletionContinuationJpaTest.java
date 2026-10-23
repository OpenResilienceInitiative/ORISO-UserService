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

/**
 * Actual inactivity private claim plus JPA deletion owner; signed HTTP is not native acceptance.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  JpaAuditingConfiguration.class,
  ScheduledTaskClaimWriter.class,
  ScheduledTaskClaimService.class,
  InactivityDeletionContinuationJpaTest.TimeConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class InactivityDeletionContinuationJpaTest {
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
  @Autowired private de.caritas.cob.userservice.api.port.out.UserChatRepository userChats;
  @Autowired private de.caritas.cob.userservice.api.port.out.UserMobileTokenRepository mobileTokens;
  @Autowired private de.caritas.cob.userservice.api.port.out.IdentityTombstoneRepository tombstones;
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
  private final AtomicInteger preparations = new AtomicInteger();
  private String savedBody;
  private String savedReply;
  private boolean loseReply;
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
    when(matrix.getAccountLocked(anyString()))
        .thenAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals(1, nativeEffects.get());
              return Optional.of(false);
            });
    doAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals("ACKNOWLEDGED", status());
              List<String> ids = call.getArgument(0);
              forgotten.add(List.copyOf(ids));
              if (loseMediaReply) {
                loseMediaReply = false;
                throw new ResourceAccessException("fixture loses owned media forget reply");
              }
              return null;
            })
        .when(media)
        .forget(anyList());
    http = new RestTemplate();
    server = MockRestServiceServer.bindTo(http).build();
    server
        .expect(
            manyTimes(),
            outbound ->
                assertTrue(
                    Set.of(ENDPOINT + "/read", ENDPOINT + "/delete", ENDPOINT + "/deactivate")
                        .contains(outbound.getURI().toString())))
        .andRespond(
            outbound -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals(HttpMethod.POST, outbound.getMethod());
              assertNull(outbound.getHeaders().getFirst("X-ORISO-Human-Authorization"));
              assertEquals(
                  "Bearer fixture-account-admin", outbound.getHeaders().getFirst("Authorization"));
              String command =
                  outbound
                      .getURI()
                      .getPath()
                      .substring(outbound.getURI().getPath().lastIndexOf('/') + 1);
              String raw = ((MockClientHttpRequest) outbound).getBodyAsString();
              var body = codec.read(raw, JsonNode.class);
              verifyCapturedProof(
                  outbound.getHeaders().getFirst("X-ORISO-Task-Context"), command, body);
              if ("read".equals(command)) {
                nativeReads.incrementAndGet();
                if (nativeEffects.get() > 0)
                  return org.springframework.test.web.client.response.MockRestResponseCreators
                      .withStatus(org.springframework.http.HttpStatus.NOT_FOUND)
                      .createResponse(outbound);
                var response =
                    withSuccess(projection(), MediaType.APPLICATION_JSON).createResponse(outbound);
                response.getHeaders().setCacheControl("no-store");
                return response;
              }
              if (!"delete".equals(command))
                return org.springframework.test.web.client.response.MockRestResponseCreators
                    .withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
                    .createResponse(outbound);
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
  void duePrivateClaimDeletesActualActiveUserWithoutInventingHumanDeletionDate() {
    var service = inactivity(true, false);
    assertEquals(
        "ACTIVE",
        jdbc.queryForObject(
            "select deletion_lifecycle_state from user where user_id=?", String.class, ACCOUNT));
    assertNull(
        jdbc.queryForObject(
            "select delete_date from user where user_id=?", java.sql.Timestamp.class, ACCOUNT));
    service.scan(false);
    assertActualDeletionClaim();
    assertEquals(
        1, nativeEffects.get(), "The actual due private cause must reach scoped native deletion");
    assertEquals(1, callbacks.get());
    assertEquals(
        1, preparations.get(), "The actual same-frame preparation precedes final deletion");
    assertTrue(users.findById(ACCOUNT).isEmpty());
    assertEquals(
        de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService.Status
            .DELETED,
        service.snapshot(ACCOUNT).orElseThrow().status());
    assertEquals("APPLIED", status());
    var envelope = envelope();
    assertEquals(
        Set.of(
            "representation", "originalRow", "rowDigest", "originalSql", "body", "bindingDigest"),
        fields(envelope));
    assertEquals("CLAIMED_INACTIVITY_DELETE_V1", envelope.path("representation").textValue());
    assertEquals(USER_FIELDS, fields(envelope.path("originalSql")));
    assertEquals(
        "ACTIVE", envelope.path("originalSql").path("deletion_lifecycle_state").textValue());
    assertTrue(envelope.path("originalSql").path("delete_date").isNull());
    assertEquals("DELETE", envelope.path("originalRow").path("action").textValue());
    assertEquals("DELETING", envelope.path("originalRow").path("status").textValue());
    assertEquals(
        envelope.path("originalRow").path("workId"), envelope.path("body").path("operationId"));
    assertEquals(
        envelope.path("body").path("operationId"),
        envelope.path("body").path("target").path("origin").path("workflowId"));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from scheduled_task_claim where task_name='account-deletion'",
            Integer.class));
    verifyNoInteractions(sdk);
  }

  @Test
  void originallyRealmOnlyClaimRemainsAbsentAndIssuesOnlyItsOriginalIdentity() {
    users.deleteById(ACCOUNT);
    var service = inactivity(true, false);
    service.scan(false);
    assertActualDeletionClaim();
    assertEquals(1, nativeEffects.get());
    assertEquals(1, callbacks.get());
    assertEquals(ACCOUNT, completedIdentity);
    assertTrue(envelope().path("originalSql").isNull());
    assertTrue(users.findById(ACCOUNT).isEmpty());
    assertEquals("APPLIED", status());
    assertEquals(
        de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService.Status
            .DELETED,
        service.snapshot(ACCOUNT).orElseThrow().status());
    verifyNoInteractions(sdk);
  }

  @Test
  void unknownNativeReplyRetainsTheSameActualClaimAndFrozenBodyUntilAtomicCompletion() {
    var service = inactivity(true, false);
    loseReply = true;
    service.scan(false);
    assertActualDeletionClaim();
    assertEquals(1, nativeEffects.get());
    assertEquals(0, callbacks.get());
    assertEquals("FROZEN", status());
    assertTrue(users.findById(ACCOUNT).isPresent());
    String originalEnvelope =
        jdbc.queryForObject(
            "select frozen_body from identity_maintenance_intent where subject_id=?",
            String.class,
            ACCOUNT);
    String originalClaim =
        jdbc.queryForObject(
            "select claimed_json from account_inactivity where identity_id=?",
            String.class,
            ACCOUNT);
    var resumed = inactivity(false, false);
    resumed.scan(false);
    assertEquals(1, nativeEffects.get());
    assertEquals(2, nativeCalls.get());
    assertEquals(1, callbacks.get());
    assertEquals(
        originalEnvelope,
        jdbc.queryForObject(
            "select frozen_body from identity_maintenance_intent where subject_id=?",
            String.class,
            ACCOUNT));
    assertEquals(
        originalClaim,
        jdbc.queryForObject(
            "select claimed_json from account_inactivity where identity_id=?",
            String.class,
            ACCOUNT));
    assertEquals("APPLIED", status());
    assertTrue(users.findById(ACCOUNT).isEmpty());
    verifyNoInteractions(sdk);
  }

  @Test
  void retainedExactActiveUserRemainsRetryableUnderTheAcknowledgedInactivityCause() {
    retainLocalUser = true;
    var service = inactivity(true, false);
    service.scan(false);
    assertActualDeletionClaim();
    assertEquals(1, nativeEffects.get());
    assertEquals(1, callbacks.get());
    assertEquals("ACKNOWLEDGED", status());
    assertEquals(
        de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService.Status
            .DELETING,
        service.snapshot(ACCOUNT).orElseThrow().status());
    assertEquals(
        "ACTIVE",
        jdbc.queryForObject(
            "select deletion_lifecycle_state from user where user_id=?", String.class, ACCOUNT));
    assertNull(
        jdbc.queryForObject(
            "select delete_date from user where user_id=?", java.sql.Timestamp.class, ACCOUNT));
    retainLocalUser = false;
    inactivity(false, false).scan(false);
    assertEquals(1, nativeCalls.get(), "Saved native ACK must skip another native delete");
    assertEquals(2, callbacks.get());
    assertEquals("APPLIED", status());
    assertTrue(users.findById(ACCOUNT).isEmpty());
    verifyNoInteractions(sdk);
  }

  @Test
  void notDueAndMixedRolesNeverAuthorizeDeletion() {
    var notDue = inactivity(true, true);
    notDue.scan(false);
    assertEquals(0, nativeReads.get());
    assertEquals(0, nativeEffects.get());
    assertEquals(0, callbacks.get());
    var service = inactivity(false, false);
    jdbc.update(
        "update account_inactivity set last_activity=?,due_at=? where identity_id=?",
        java.sql.Timestamp.from(clock.instant().minusSeconds(800L * 86400)),
        java.sql.Timestamp.from(clock.instant().minusSeconds(1)),
        ACCOUNT);
    projectedRoles = List.of("user", "consultant");
    service.scan(false);
    assertEquals(0, nativeEffects.get());
    assertEquals(0, callbacks.get());
    assertTrue(users.findById(ACCOUNT).isPresent());
    verifyNoInteractions(sdk);
  }

  @Test
  void replacedOriginalSqlIncarnationCannotResumeTheUnknownNativeCommand() {
    var service = inactivity(true, false);
    loseReply = true;
    service.scan(false);
    assertActualDeletionClaim();
    assertEquals(1, nativeEffects.get());
    assertEquals("FROZEN", status());
    String original =
        jdbc.queryForObject(
            "select frozen_body from identity_maintenance_intent where subject_id=?",
            String.class,
            ACCOUNT);
    jdbc.update(
        "update user set create_date=dateadd('DAY',1,create_date),username=? where user_id=?",
        new UsernameTranscoder().encodeUsername("replacement-cleanup"),
        ACCOUNT);
    inactivity(false, false).scan(false);
    assertEquals(0, callbacks.get());
    assertEquals(1, nativeCalls.get());
    assertEquals(
        original,
        jdbc.queryForObject(
            "select frozen_body from identity_maintenance_intent where subject_id=?",
            String.class,
            ACCOUNT));
    assertTrue(users.findById(ACCOUNT).isPresent());
    verifyNoInteractions(sdk);
  }

  @Test
  void mediaTimeoutRetainsTheExactAcknowledgedSetAndBothJournalsUntilIdempotentRetry() {
    withMatrixIdentity = true;
    loseMediaReply = true;
    var service = inactivity(true, false);
    service.scan(false);
    assertEquals(1, nativeEffects.get());
    assertEquals("ACKNOWLEDGED", status());
    assertTrue(users.findById(ACCOUNT).isPresent());
    assertEquals(
        de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService.Status
            .DELETING,
        service.snapshot(ACCOUNT).orElseThrow().status());
    assertEquals(List.of(List.of("@owned-cleanup:fixture.invalid")), forgotten);
    String saved = codec.canonical(envelope());
    inactivity(false, false).scan(false);
    assertEquals(1, nativeCalls.get(), "Durable ACK skips another native command");
    assertEquals(
        List.of(
            List.of("@owned-cleanup:fixture.invalid"), List.of("@owned-cleanup:fixture.invalid")),
        forgotten);
    assertEquals(saved, codec.canonical(envelope()));
    assertEquals("APPLIED", status());
    assertTrue(users.findById(ACCOUNT).isEmpty());
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from account_inactivity_matrix_state where identity_id=?",
            Integer.class,
            ACCOUNT));
    verifyNoInteractions(sdk);
  }

  @Test
  void contentVetoCannotForgetMediaOrFinishTheAcknowledgedInactivityCause() {
    withMatrixIdentity = true;
    retainLocalUser = true;
    var service = inactivity(true, false);
    service.scan(false);
    assertEquals(1, nativeEffects.get());
    assertEquals("ACKNOWLEDGED", status());
    assertTrue(forgotten.isEmpty());
    assertTrue(users.findById(ACCOUNT).isPresent());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from account_inactivity_matrix_state where identity_id=?",
            Integer.class,
            ACCOUNT));
    assertEquals(
        de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService.Status
            .DELETING,
        service.snapshot(ACCOUNT).orElseThrow().status());
    verifyNoInteractions(sdk);
  }

  @Test
  void lostFinalJpaCommitReplyRejoinsTheSameTwoJournalCompletionAndNeverRepeatsCleanup() {
    uncertainFinal =
        new LostCommitReplyJpaManager(
            ((JpaTransactionManager) transactions).getEntityManagerFactory(), dataSource);
    var service = inactivity(true, false);
    service.scan(false);
    assertEquals("APPLIED", status());
    assertEquals(
        de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService.Status
            .DELETED,
        service.snapshot(ACCOUNT).orElseThrow().status());
    assertTrue(users.findById(ACCOUNT).isEmpty());
    assertEquals(1, callbacks.get());
    assertEquals(1, nativeCalls.get());
    inactivity(false, false).scan(false);
    assertEquals(1, callbacks.get());
    assertEquals(1, nativeCalls.get());
    verifyNoInteractions(sdk);
  }

  private final de.caritas.cob.userservice.api.adapters.keycloak.KeycloakClient sdk =
      mock(de.caritas.cob.userservice.api.adapters.keycloak.KeycloakClient.class);
  private boolean retainLocalUser;
  private boolean withMatrixIdentity;
  private boolean loseMediaReply;
  private final List<List<String>> forgotten = new ArrayList<>();
  private final de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService matrix =
      mock(de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.class);
  private final de.caritas.cob.userservice.api.workflow.accountinactivity
          .AccountInactivityMediaClient
      media =
          mock(
              de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityMediaClient
                  .class);
  private LostCommitReplyJpaManager uncertainFinal;
  private String completedIdentity;
  private List<String> projectedRoles = List.of("user");
  private Instant enrolledAt;

  private void assertActualDeletionClaim() {
    assertTrue(nativeReads.get() >= 1, "Actual checked role classification precedes deletion");
    var row =
        codec.read(
            jdbc.queryForObject(
                "select claimed_json from account_inactivity where identity_id=?",
                String.class,
                ACCOUNT),
            JsonNode.class);
    assertEquals("DELETE", row.path("action").textValue());
    assertEquals("DELETING", row.path("status").textValue());
    assertEquals("SCHEDULED", row.path("initiator").path("kind").textValue());
    assertEquals(
        UUID.fromString(row.path("workId").textValue()).toString(), row.path("workId").textValue());
    assertEquals(
        row.path("workId").textValue(),
        jdbc.queryForObject(
            "select work_id from account_inactivity where identity_id=?", String.class, ACCOUNT));
    assertNotNull(
        jdbc.queryForObject(
            "select claimed_at from account_inactivity where identity_id=?",
            java.sql.Timestamp.class,
            ACCOUNT));
    assertTrue(codec.claimedInactivityDeletionRowDigest(row).matches("[0-9a-f]{64}"));
  }

  private JsonNode envelope() {
    return codec.read(
        jdbc.queryForObject(
            "select frozen_body from identity_maintenance_intent where subject_id=?",
            String.class,
            ACCOUNT),
        JsonNode.class);
  }

  private de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService
      inactivity(boolean initialize, boolean notDue) {
    if (initialize) {
      jdbc.execute("drop table if exists account_inactivity");
      jdbc.execute("drop table if exists account_inactivity_journal");
      jdbc.execute(
          "create table account_inactivity(identity_id varchar(36) primary key,tenant_id bigint,assigned_months int not null,revision bigint not null,last_activity timestamp(6) not null,due_at timestamp(6) not null,status varchar(20) not null,last_error varchar(1000),attempts int default 0 not null)");
      jdbc.execute(
          "create table account_inactivity_journal(journal_id bigint generated by default as identity primary key,identity_id varchar(36),action varchar(30),target varchar(30),outcome varchar(40),created_at timestamp(6))");
      try (var stream =
          new ClassPathResource(
                  "db/changelog/changeset/20261023_inactivity_claimed_work_367/migrate.sql")
              .getInputStream()) {
        String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        String[] columns =
            migration
                .replaceFirst("(?s)^.*?ALTER TABLE account_inactivity\\s+ADD COLUMN\\s+", "")
                .replaceFirst(";\\s*$", "")
                .split(",\\s*ADD COLUMN\\s+");
        assertEquals(13, columns.length);
        for (String column : columns)
          jdbc.execute("ALTER TABLE account_inactivity ADD COLUMN " + column);
      } catch (java.io.IOException invalid) {
        throw new AssertionError(invalid);
      }
      jdbc.execute("drop table if exists account_inactivity_access_state");
      jdbc.execute("drop table if exists account_inactivity_matrix_state");
      new ResourceDatabasePopulator(
              new ClassPathResource(
                  "db/changelog/changeset/20260916_account_inactivity_access/migrate.sql"))
          .execute(dataSource);
      jdbc.execute("drop table if exists general_admin_identity_intent");
      new ResourceDatabasePopulator(
              new ClassPathResource(
                  "db/changelog/changeset/20261022_general_admin_identity_367/migrate.sql"))
          .execute(dataSource);
      Instant birth =
          clock
              .instant()
              .minusSeconds((notDue ? 30L : 800L) * 86400)
              .truncatedTo(ChronoUnit.MICROS);
      enrolledAt = birth;
      jdbc.update(
          "update user set create_date=?,delete_date=null,deletion_lifecycle_state='ACTIVE',deletion_read_only_until=null,matrix_user_id=? where user_id=?",
          java.sql.Timestamp.from(birth),
          withMatrixIdentity ? "@owned-cleanup:fixture.invalid" : null,
          ACCOUNT);
    }
    var mode = mock(de.caritas.cob.userservice.api.port.out.IdentityClientConfig.class);
    when(mode.isFiveActorMode()).thenReturn(true);
    var effects =
        new de.caritas.cob.userservice.api.workflow.accountinactivity
            .DefaultAccountInactivityEffects(
            jdbc,
            transactions,
            sdk,
            matrix,
            media,
            new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(InactiveAskerDeletionService.class));
    effects.setIdentityMode(mode);
    var deletion =
        owner(
            true,
            frame -> {
              assertTrue(frame.inactivityCause());
              assertEquals(ACCOUNT, frame.accountId());
              assertEquals(7L, frame.tenantId());
              assertFalse(frame.completed());
              completedIdentity = frame.accountId();
              callbacks.incrementAndGet();
              if (frame.hasLocalUser()) {
                var dto =
                    new java.util.concurrent.atomic.AtomicReference<
                        de.caritas.cob.userservice.api.workflow.delete.model
                            .AskerDeletionWorkflowDTO>();
                frame.runOwnedLocal(
                    () ->
                        dto.set(
                            new de.caritas.cob.userservice.api.workflow.delete.model
                                .AskerDeletionWorkflowDTO(
                                users.findById(ACCOUNT).orElseThrow(), new ArrayList<>(), frame)));
                if (retainLocalUser)
                  dto.get()
                      .getDeletionWorkflowErrors()
                      .add(
                          de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError
                              .builder()
                              .deletionTargetType(
                                  de.caritas.cob.userservice.api.workflow.delete.model
                                      .DeletionTargetType.USER_CONTENT)
                              .build());
                var finalAction =
                    new de.caritas.cob.userservice.api.workflow.delete.action.asker
                        .DeleteDatabaseAskerAction(
                        users, userChats, mobileTokens, new IdentityTombstoneService(tombstones));
                var prepared = finalAction.prepareAcknowledged(dto.get());
                if (retainLocalUser) {
                  assertTrue(
                      prepared.isEmpty(), "A real content veto must precede media forgetting");
                  return;
                }
                var proof = prepared.orElseThrow();
                preparations.incrementAndGet();
                frame.beforeFinalLocalDeletion(proof);
                if (uncertainFinal != null) uncertainFinal.loseNextCommit = true;
                assertTrue(finalAction.completeAcknowledged(proof));
              } else {
                assertTrue(users.findById(ACCOUNT).isEmpty());
                frame.beforeFinalLocalAbsence();
                assertTrue(frame.completeOwnedLocalDeletion(() -> {}));
              }
            });
    effects.setScopedDeletionMaintenance(deletion);
    var service =
        new de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService(
            jdbc, transactions, clock, effects);
    service.setIdentityMode(mode);
    service.configureScopedInactivity(
        new de.caritas.cob.userservice.api.adapters.keycloak.maintenance
            .KeycloakInactivityMaintenance(
            http,
            actors,
            broker,
            codec,
            "https://identity.test",
            "fixture",
            REALM,
            "https://identity.test/realms/fixture"),
        new de.caritas.cob.userservice.api.helper.AuthenticatedUser(),
        new org.springframework.mock.web.MockHttpServletRequest(),
        "https://identity.test/realms/fixture",
        Set.of("admin"));
    if (initialize) service.assignAtCreation(ACCOUNT, 7L, 24, 0, enrolledAt);
    return service;
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

  private ScheduledAskerMaintenance owner(
      boolean configureJpa, Consumer<AcknowledgedDeletion> cleanup) {
    return owner(configureJpa, cleanup, uncertainFinal == null ? transactions : uncertainFinal);
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
        throw new IllegalStateException("fixture loses only the reply after real final JPA commit");
      }
    }
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
    row.put("effectiveRoles", projectedRoles);
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
