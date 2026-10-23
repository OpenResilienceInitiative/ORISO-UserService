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
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import de.caritas.cob.userservice.api.workflow.delete.service.ScheduledConsultantMaintenance.AcknowledgedConsultantDeletion;
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

/** Actual Consultant/JPA/lease owner tests; the signed HTTP double is not native acceptance. */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  JpaAuditingConfiguration.class,
  ScheduledTaskClaimWriter.class,
  ScheduledTaskClaimService.class,
  ConsultantDeletionContinuationJpaTest.TimeConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ConsultantDeletionContinuationJpaTest {
  private static final String ACCOUNT = "11111111-1111-4111-8111-111111111111";
  private static final String REALM = "22222222-2222-4222-8222-222222222222";
  private static final String SUBJECT = "33333333-3333-4333-8333-333333333333";
  private static final String ENDPOINT =
      "https://identity.test/realms/fixture/oriso-account-maintenance/v1/accounts/" + ACCOUNT;
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private static final String STATE = "a".repeat(64);
  private static final List<String> CONSULTANT_COLUMNS =
      List.of(
          "managed_paused",
          "consultant_id",
          "matrix_user_id",
          "chat_recovery_mode",
          "chat_recovery_policy_revision",
          "username",
          "first_name",
          "last_name",
          "email",
          "is_absent",
          "is_team_consultant",
          "is_supervisor",
          "assigned_supervisor_id",
          "display_name",
          "internal_display_name",
          "salutation",
          "position",
          "title",
          "admin_remarks",
          "avatar_id",
          "avatar_kind",
          "public_slug",
          "pending_public_slug",
          "public_slug_status",
          "public_slug_reviewed_at",
          "absence_message",
          "language_formal",
          "id_old",
          "create_date",
          "update_date",
          "delete_date",
          "deletion_lifecycle_state",
          "deletion_read_only_until",
          "deletion_paused_until",
          "deletion_pause_reason",
          "deletion_paused_by",
          "deletion_pause_created_at",
          "encourage_2fa",
          "two_factor_required",
          "topic_permission",
          "password_change_required",
          "live_chat_via_sidebar",
          "magic_link_login_enabled",
          "notify_enquiries_repeating",
          "notify_new_chat_message_from_advice_seeker",
          "tenant_id",
          "status",
          "walk_through_enabled",
          "language_code",
          "terms_and_conditions_confirmation",
          "data_privacy_confirmation",
          "notifications_enabled",
          "notifications_settings");
  private static final Set<String> SQL_BOOLEAN_COLUMNS =
      Set.of(
          "managed_paused",
          "is_absent",
          "is_team_consultant",
          "is_supervisor",
          "language_formal",
          "encourage_2fa",
          "two_factor_required",
          "password_change_required",
          "live_chat_via_sidebar",
          "magic_link_login_enabled",
          "notify_enquiries_repeating",
          "notify_new_chat_message_from_advice_seeker",
          "walk_through_enabled",
          "notifications_enabled");
  private static final Set<String> SQL_TIMESTAMP_COLUMNS =
      Set.of(
          "public_slug_reviewed_at",
          "create_date",
          "update_date",
          "delete_date",
          "deletion_read_only_until",
          "deletion_paused_until",
          "deletion_pause_created_at",
          "terms_and_conditions_confirmation",
          "data_privacy_confirmation");
  private static final Set<String> SQL_LONG_COLUMNS =
      Set.of("chat_recovery_policy_revision", "id_old", "tenant_id");
  private Map<String, Object> expectedOriginalSql53;
  @Autowired private DataSource dataSource;
  @Autowired private ConsultantRepository consultants;
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
  private final AtomicInteger nativeCalls = new AtomicInteger();
  private final AtomicInteger nativeEffects = new AtomicInteger();
  private final AtomicInteger callbacks = new AtomicInteger();
  private String savedBody;
  private String savedReply;
  private boolean loseReply;
  private boolean forgeReply;
  private boolean retain;
  private Consultant originalEntity;
  private PlatformTransactionManager ownerTransactions;
  private String projectedUsername = "owned-cleanup";
  private List<String> projectedRoles = List.of("consultant");

  @BeforeEach
  void actualJpaConsultantAndExistingLeaseSchema() {
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
    consultants.findById(ACCOUNT).ifPresent(consultants::delete);
    users.findById("00000000-0000-4000-8000-000000000000").ifPresent(users::delete);
    consultants.findById("00000000-0000-4000-8000-000000000000").ifPresent(consultants::delete);
    var now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    var consultant =
        Consultant.builder()
            .id(ACCOUNT)
            .username(new UsernameTranscoder().encodeUsername("owned-cleanup"))
            .firstName("Owned")
            .lastName("Consultant")
            .email("ordinary@fixture.invalid")
            .tenantId(7L)
            .languageCode(LanguageCode.en)
            .encourage2fa(false)
            .magicLinkLoginEnabled(false)
            .notifyEnquiriesRepeating(true)
            .notifyNewChatMessageFromAdviceSeeker(true)
            .matrixUserId("@owned-cleanup:fixture.invalid")
            .createDate(now.minusYears(3))
            .updateDate(now.minusDays(3))
            .deleteDate(now.minusDays(3))
            .deletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD)
            .deletionReadOnlyUntil(now.minusDays(1))
            .build();
    consultants.save(consultant);
    originalEntity = consultant;
    assertNotNull(
        jdbc.queryForObject(
            "select create_date from consultant where consultant_id=?",
            java.sql.Timestamp.class,
            ACCOUNT));
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
                if (expectedOriginalSql53 == null) expectedOriginalSql53 = independentlyRead53();
                var response =
                    withSuccess(projection(), MediaType.APPLICATION_JSON).createResponse(outbound);
                response.getHeaders().setCacheControl("no-store");
                return response;
              }
              nativeCalls.incrementAndGet();
              if (savedBody == null) {
                savedBody = raw;
                savedReply = receipt(body);
                nativeEffects.incrementAndGet();
              } else
                assertEquals(savedBody, raw, "Unknown cannot allocate another body or operation");
              if (loseReply) {
                loseReply = false;
                throw new ResourceAccessException("fixture loses native reply");
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
  void dueExclusiveConsultantRequiresActualPrivateAckBeforeRealFinalJpaRemoval() {
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(1, nativeEffects.get(), "Actual due Consultant must reach native deletion");
    assertEquals(1, callbacks.get());
    assertTrue(consultants.findById(ACCOUNT).isEmpty());
    assertEquals("APPLIED", status());
    assertEnvelope();
  }

  @Test
  void groupChatConsultantUsesTheSameClosedDeletionContract() {
    projectedRoles = List.of("consultant", "group-chat-consultant");
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(1, nativeEffects.get());
    assertEquals(1, callbacks.get());
    assertTrue(consultants.findById(ACCOUNT).isEmpty());
    assertEquals("APPLIED", status());
  }

  @Test
  void unknownNativeReplyKeepsOriginalEnvelopeAndRetriesExactBodyOnRestart() {
    loseReply = true;
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(1, nativeEffects.get());
    assertEquals(0, callbacks.get());
    assertEquals("FROZEN", status());
    String original = envelopeRaw();
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(1, nativeEffects.get());
    assertEquals(2, nativeCalls.get());
    assertEquals(1, callbacks.get());
    assertEquals(original, envelopeRaw());
    assertEquals("APPLIED", status());
  }

  @Test
  void forgedNativeReceiptNeverIssuesContinuationOrRemovesConsultant() {
    forgeReply = true;
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(1, nativeEffects.get());
    assertEquals(0, callbacks.get());
    assertEquals("FROZEN", status());
    assertTrue(consultants.findById(ACCOUNT).isPresent());
  }

  @Test
  void savedAckColdRestartSkipsOnlyNativeAndCompletesSameOriginalRow() {
    retain = true;
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(1, nativeEffects.get());
    assertEquals(1, callbacks.get());
    assertEquals("ACKNOWLEDGED", status());
    String original = envelopeRaw();
    retain = false;
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(1, nativeCalls.get());
    assertEquals(2, callbacks.get());
    assertEquals(original, envelopeRaw());
    assertEquals("APPLIED", status());
  }

  @Test
  void notDueAndPausedRowsCannotAllocateOrDispatch() {
    jdbc.update(
        "update consultant set deletion_read_only_until=? where consultant_id=?",
        LocalDateTime.now(ZoneOffset.UTC).plusDays(1),
        ACCOUNT);
    owner(true, this::complete).performDeletionWorkflow();
    jdbc.update(
        "update consultant set deletion_read_only_until=?,deletion_paused_until=? where consultant_id=?",
        LocalDateTime.now(ZoneOffset.UTC).minusDays(1),
        LocalDateTime.now(ZoneOffset.UTC).plusDays(1),
        ACCOUNT);
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(0, nativeReads.get());
    assertEquals(0, nativeEffects.get());
    assertEquals(0, intentCount());
  }

  @Test
  void missingConsumerOrActualJpaOwnerStopsBeforeNativeReadAndEffect() {
    owner(false, this::complete).performDeletionWorkflow();
    owner(true, null).performDeletionWorkflow();
    assertEquals(0, nativeReads.get());
    assertEquals(0, nativeCalls.get());
    assertEquals(0, callbacks.get());
    assertTrue(consultants.findById(ACCOUNT).isPresent());
  }

  @Test
  void mixedLocalUserCannotBorrowExclusiveConsultantDeletion() {
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
    users.save(user);
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(0, nativeReads.get());
    assertEquals(0, nativeEffects.get());
    assertEquals(0, intentCount());
  }

  @Test
  void combinedNativeRolesCannotReachDeleteOrContinuation() {
    projectedRoles = List.of("consultant", "restricted-agency-admin", "user-admin");
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(1, nativeReads.get());
    assertEquals(0, nativeEffects.get());
    assertEquals(0, callbacks.get());
  }

  @Test
  void changedSqlBirthAfterUnknownCannotRemintAroundDifferentDeleteDate() {
    loseReply = true;
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(1, nativeEffects.get());
    String original = envelopeRaw();
    jdbc.update(
        "update consultant set create_date=?,delete_date=? where consultant_id=?",
        LocalDateTime.now(ZoneOffset.UTC),
        LocalDateTime.now(ZoneOffset.UTC).minusDays(2),
        ACCOUNT);
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(1, nativeCalls.get());
    assertEquals(1, intentCount());
    assertEquals(original, envelopeRaw());
    assertEquals(0, callbacks.get());
  }

  @Test
  void currentFrameRejectsSubstitutedConsultantAndScalarSqlDrift() {
    owner(
            true,
            frame -> {
              callbacks.incrementAndGet();
              var detached = consultants.findById(ACCOUNT).orElseThrow();
              detached.setMatrixUserId("@foreign:fixture.invalid");
              assertThrows(RuntimeException.class, () -> frame.requireCurrentConsultant(detached));
              jdbc.update(
                  "update consultant set admin_remarks=? where consultant_id=?",
                  "changed scalar",
                  ACCOUNT);
              assertThrows(RuntimeException.class, frame::requireCurrent);
            })
        .performDeletionWorkflow();
    assertEquals(1, nativeEffects.get());
    assertEquals(1, callbacks.get());
    assertTrue(consultants.findById(ACCOUNT).isPresent());
    assertEquals("ACKNOWLEDGED", status());
  }

  @Test
  void fullActualLobTextIsBoundWithoutWideningTheGenericParser() {
    String lob = "line\n🦉".repeat(18000);
    jdbc.update("update consultant set absence_message=? where consultant_id=?", lob, ACCOUNT);
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(1, nativeEffects.get());
    assertEquals(1, callbacks.get());
    assertEnvelope();
    assertTrue(envelopeRaw().getBytes(StandardCharsets.UTF_8).length < 65536);
    assertFalse(envelopeRaw().contains("🦉"));
  }

  @Test
  void pendingLegacyConsultantGetsTheRealSafeguardWindowBeforeDispatch() {
    jdbc.update(
        "update consultant set deletion_lifecycle_state='PENDING_DELETION',deletion_read_only_until=null where consultant_id=?",
        ACCOUNT);
    var deletion =
        jdbc.queryForObject(
            "select delete_date from consultant where consultant_id=?",
            java.sql.Timestamp.class,
            ACCOUNT);
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(
        "READ_ONLY_SAFEGUARD",
        jdbc.queryForObject(
            "select deletion_lifecycle_state from consultant where consultant_id=?",
            String.class,
            ACCOUNT));
    assertTrue(
        jdbc.queryForObject(
                "select deletion_read_only_until from consultant where consultant_id=?",
                java.sql.Timestamp.class,
                ACCOUNT)
            .toLocalDateTime()
            .isAfter(LocalDateTime.now(ZoneOffset.UTC)));
    assertEquals(
        deletion,
        jdbc.queryForObject(
            "select delete_date from consultant where consultant_id=?",
            java.sql.Timestamp.class,
            ACCOUNT));
    assertEquals(0, nativeReads.get());
    assertEquals(0, intentCount());
  }

  @Test
  void expiredPauseRemainsActualDataAndDoesNotBlockTheDueConsultant() {
    var expired = LocalDateTime.now(ZoneOffset.UTC).minusDays(2).truncatedTo(ChronoUnit.MICROS);
    jdbc.update(
        "update consultant set deletion_paused_until=?,deletion_pause_reason='original pause' where consultant_id=?",
        expired,
        ACCOUNT);
    retain = true;
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(1, nativeEffects.get());
    assertEquals("ACKNOWLEDGED", status());
    assertEquals(
        expired,
        jdbc.queryForObject(
                "select deletion_paused_until from consultant where consultant_id=?",
                java.sql.Timestamp.class,
                ACCOUNT)
            .toLocalDateTime());
    assertEnvelope();
  }

  @Test
  void aMixedPoisonCandidateCannotPreventAnotherExclusiveConsultant() {
    String poison = "00000000-0000-4000-8000-000000000000";
    var other = new Consultant();
    org.springframework.beans.BeanUtils.copyProperties(originalEntity, other);
    other.setId(poison);
    other.setUsername(new UsernameTranscoder().encodeUsername("mixed-poison"));
    other.setEmail("poison@fixture.invalid");
    consultants.save(other);
    var mixed =
        new User(
            poison,
            null,
            new UsernameTranscoder().encodeUsername("mixed-poison"),
            "poison@fixture.invalid",
            false);
    mixed.setTenantId(7L);
    mixed.setLanguageCode(LanguageCode.en);
    mixed.setEncourage2fa(false);
    users.save(mixed);
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(1, nativeEffects.get());
    assertEquals(1, callbacks.get());
    assertEquals("APPLIED", status());
    assertTrue(consultants.findById(poison).isPresent());
    consultants.deleteById(poison);
    users.deleteById(poison);
  }

  @Test
  void appliedReceiptCannotAdoptARecreatedConsultantWithTheSameUuid() {
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(1, nativeEffects.get());
    assertEquals("APPLIED", status());
    var replacement = new Consultant();
    org.springframework.beans.BeanUtils.copyProperties(originalEntity, replacement);
    replacement.setCreateDate(LocalDateTime.now(ZoneOffset.UTC));
    replacement.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC).minusDays(2));
    consultants.save(replacement);
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(1, nativeCalls.get());
    assertEquals(1, callbacks.get());
    assertEquals(1, intentCount());
    assertTrue(consultants.findById(ACCOUNT).isPresent());
  }

  @Test
  void actualFinalCommitLostReplyResolvesOnlyAppliedAndNeverRepeatsNative() {
    var uncertain = new LostCommitReplyJpaManager(entities.getEntityManagerFactory(), dataSource);
    ownerTransactions = uncertain;
    owner(
            true,
            frame -> {
              callbacks.incrementAndGet();
              assertThrows(
                  IllegalStateException.class,
                  () ->
                      frame.completeOwnedLocalDeletion(
                          () -> {
                            var actual = consultants.findById(ACCOUNT).orElseThrow();
                            frame.requireCurrentConsultant(actual);
                            consultants.delete(actual);
                            uncertain.loseNextCommit = true;
                          }));
              assertTrue(frame.completed());
            })
        .performDeletionWorkflow();
    assertEquals(1, nativeEffects.get());
    assertEquals(1, callbacks.get());
    assertEquals("APPLIED", status());
    assertTrue(consultants.findById(ACCOUNT).isEmpty());
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(1, nativeCalls.get());
    assertEquals(1, callbacks.get());
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
        throw new IllegalStateException("fixture loses reply after actual Consultant final commit");
      }
    }
  }

  @Test
  void uppercaseOriginalSqlUsernameMatchesOnlyTheExistingNativeCaseContract() {
    jdbc.update(
        "update consultant set username=? where consultant_id=?",
        new UsernameTranscoder().encodeUsername("Owned-Cleanup"),
        ACCOUNT);
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(1, nativeEffects.get());
    assertEquals(1, callbacks.get());
    assertEquals("APPLIED", status());
    assertEquals(
        new UsernameTranscoder().encodeUsername("Owned-Cleanup"),
        codec.read(envelopeRaw(), JsonNode.class).path("originalSql").path("username").textValue());
    assertEnvelope();
  }

  @Test
  void foreignNativeUsernameNeverAdoptsTheOriginalSqlAccount() {
    projectedUsername = "foreign-person";
    owner(true, this::complete).performDeletionWorkflow();
    assertEquals(1, nativeReads.get());
    assertEquals(0, nativeEffects.get());
    assertEquals(0, callbacks.get());
    assertTrue(consultants.findById(ACCOUNT).isPresent());
  }

  private void complete(AcknowledgedConsultantDeletion frame) {
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    frame.requireCurrent();
    assertEquals(ACCOUNT, frame.accountId());
    assertEquals(7L, frame.tenantId());
    assertFalse(frame.completed());
    callbacks.incrementAndGet();
    frame.runOwnedLocal(
        () -> frame.requireCurrentConsultant(consultants.findById(ACCOUNT).orElseThrow()));
    if (retain) {
      assertFalse(frame.completeOwnedLocalDeletion(() -> {}));
      return;
    }
    assertTrue(
        frame.completeOwnedLocalDeletion(
            () -> {
              var actual = consultants.findById(ACCOUNT).orElseThrow();
              frame.requireCurrentConsultant(actual);
              consultants.delete(actual);
            }));
    assertTrue(frame.completed());
  }

  private ScheduledConsultantMaintenance owner(
      boolean jpa, Consumer<AcknowledgedConsultantDeletion> cleanup) {
    var lifecycle = new DeletionLifecycleService();
    org.springframework.test.util.ReflectionTestUtils.setField(
        lifecycle, "globalReadOnlyHours", 48L);
    org.springframework.test.util.ReflectionTestUtils.setField(
        lifecycle, "tenantOverrideConfig", "");
    var owner =
        new ScheduledConsultantMaintenance(
            jdbc,
            ownerTransactions == null ? transactions : ownerTransactions,
            claims,
            clock,
            lifecycle,
            actors,
            broker,
            codec,
            http,
            "https://identity.test",
            "fixture",
            REALM);
    if (jpa) owner.configureJpaCleanupOwner(entities);
    if (cleanup != null) owner.configureAcknowledgedCleanup(cleanup);
    return owner;
  }

  private int intentCount() {
    return jdbc.queryForObject(
        "select count(*) from identity_maintenance_intent where subject_id=?",
        Integer.class,
        ACCOUNT);
  }

  private String status() {
    return jdbc.queryForObject(
        "select status from identity_maintenance_intent where subject_id=?", String.class, ACCOUNT);
  }

  private String envelopeRaw() {
    return jdbc.queryForObject(
        "select frozen_body from identity_maintenance_intent where subject_id=?",
        String.class,
        ACCOUNT);
  }

  private void assertEnvelope() {
    var envelope = codec.read(envelopeRaw(), JsonNode.class);
    assertEquals(
        Set.of("representation", "originalSql", "body", "bindingDigest"), fields(envelope));
    assertEquals(
        "CLAIMED_SCHEDULED_CONSULTANT_DELETE_V1", envelope.path("representation").textValue());
    assertEquals(
        Set.of(
            "v",
            "consultantId",
            "tenantId",
            "createDate",
            "deleteDate",
            "username",
            "matrixUserId",
            "rowDigest"),
        fields(envelope.path("originalSql")));
    assertEquals(ACCOUNT, envelope.path("originalSql").path("consultantId").textValue());
    assertTrue(envelope.path("originalSql").path("rowDigest").textValue().matches("[0-9a-f]{64}"));
    assertEquals(53, expectedOriginalSql53.size());
    assertEquals(
        HexFormat.of()
            .formatHex(
                hmac(
                    "claimed-scheduled-consultant-deletion-row/v1\n"
                        + codec.canonical(expectedOriginalSql53))),
        envelope.path("originalSql").path("rowDigest").textValue());
    var unsigned = envelope.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) unsigned).remove("bindingDigest");
    assertEquals(
        HexFormat.of()
            .formatHex(
                hmac(
                    "claimed-scheduled-consultant-deletion-intent/v1\n"
                        + codec.canonical(unsigned))),
        envelope.path("bindingDigest").textValue());
    assertEquals(
        expectedOriginalSql53.get("create_date"),
        envelope.path("originalSql").path("createDate").textValue());
    assertEquals(
        Set.of("target", "operationId", "expectedState", "change"), fields(envelope.path("body")));
  }

  private Map<String, Object> independentlyRead53() {
    return jdbc.queryForObject(
        "select " + String.join(",", CONSULTANT_COLUMNS) + " from consultant where consultant_id=?",
        (rs, index) -> {
          Map<String, Object> row = new TreeMap<>();
          for (String name : CONSULTANT_COLUMNS) {
            Object value;
            if (SQL_TIMESTAMP_COLUMNS.contains(name)) {
              var time = rs.getTimestamp(name);
              value =
                  time == null ? null : time.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();
            } else if (SQL_BOOLEAN_COLUMNS.contains(name)) {
              boolean flag = rs.getBoolean(name);
              value = rs.wasNull() ? null : flag;
            } else if (SQL_LONG_COLUMNS.contains(name)) {
              long number = rs.getLong(name);
              value = rs.wasNull() ? null : number;
            } else value = rs.getString(name);
            row.put(name, value);
          }
          return row;
        },
        ACCOUNT);
  }

  private static Set<String> fields(JsonNode value) {
    var result = new HashSet<String>();
    value.fieldNames().forEachRemaining(result::add);
    return result;
  }

  private String projection() {
    var row = new TreeMap<String, Object>();
    row.put("id", ACCOUNT);
    row.put("username", projectedUsername);
    row.put("email", "ordinary@fixture.invalid");
    row.put("firstName", "Owned");
    row.put("lastName", "Consultant");
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
    Clock ownedConsultantDeletionClock() {
      return Clock.systemUTC();
    }
  }
}
