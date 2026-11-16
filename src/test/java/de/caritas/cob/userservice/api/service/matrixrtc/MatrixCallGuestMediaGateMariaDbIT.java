package de.caritas.cob.userservice.api.service.matrixrtc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.MatrixCallDevice;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallGuestRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.matrix.DedicatedCallRoom;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallBindingService;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallBindingWriter;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallConversationResolver;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallInviteNotificationService;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallLifecycleService;
import de.caritas.cob.userservice.api.service.matrix.OrisoRoomRegistry;
import de.caritas.cob.userservice.api.service.matrix.SessionCallAuthority;
import de.caritas.cob.userservice.api.service.matrix.SessionCallRevival;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;

/**
 * Security review 5901360577 (Shirloin), point 1, on MariaDB: the media gate that admits a call
 * guest applies the 1:1 rule (the assigned, active counsellor's authority and a counsellor in the
 * call), and the lifecycle takes the seat back once the rule stops holding. A guest is admitted by
 * the counsellor; then the session is reassigned, the counsellor is suspended, or the counsellor's
 * membership runs out while the asker and the guest stay. Only Matrix and the TenantService are
 * simulated.
 */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  SessionCallAuthority.class,
  SessionCallRevival.class,
  MatrixCallBindingService.class,
  MatrixCallBindingWriter.class,
  MatrixCallConversationResolver.class,
  MatrixCallLifecycleService.class,
  MatrixCallGuestService.class,
  DedicatedCallRoom.class,
  OrisoRoomRegistry.class,
  MatrixCallInviteNotificationService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MatrixCallGuestMediaGateMariaDbIT {
  private static final MariaDBContainer<?> DATABASE =
      new MariaDBContainer<>(System.getProperty("it.mariadb.image", "mariadb:10.11"))
          .withDatabaseName("userservice");

  static {
    DATABASE.start();
  }

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add(
        "spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MariaDBDialect");
    registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
    registry.add("spring.sql.init.mode", () -> "never");
    registry.add(
        "spring.datasource.hikari.transaction-isolation", () -> "TRANSACTION_REPEATABLE_READ");
    // Retry a failed removal at once: these tests run the lifecycle pass directly.
    registry.add("matrix.calls.guest-revoke-retry-ms", () -> "0");
  }

  @Autowired private MatrixCallLifecycleService lifecycle;
  @Autowired private MatrixCallInviteNotificationService reconciler;
  @Autowired private MatrixCallGuestService callGuests;
  @Autowired private MatrixCallBindingRepository bindings;
  @Autowired private MatrixCallGuestRepository guests;
  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;
  @Autowired private SessionRepository sessions;
  @Autowired private ChatRepository chats;
  @Autowired private SessionSupervisorRepository supervisors;
  @Autowired private TeamDiscussionRepository discussions;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private javax.sql.DataSource dataSource;

  @MockitoBean private MatrixSynapseService matrix;
  @MockitoBean private EventNotificationService notifications;
  @MockitoBean private AccountInactivityService accountLifecycle;
  @MockitoBean private CallParticipantEviction eviction;

  private final TenantService tenants = mock(TenantService.class);
  private MatrixRtcCallPolicyService gate;
  private String source;
  private String media;
  private String guestMatrixId;
  private String guestId;
  private Consultant counsellor;
  private Consultant colleague;
  private Long sessionId;
  private Long bindingId;
  private String lateMedia;
  private Long lateBindingId;

  @BeforeEach
  void setUp() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    source = "!gate-source-" + suffix + ":example";
    media = "!gate-media-" + suffix + ":example";
    String askerMatrixId = "@gate-asker-" + suffix + ":example";
    guestMatrixId = "@gate-guest-" + suffix + ":example";
    guestId = "gate-guest-" + suffix;
    var transaction = new TransactionTemplate(transactionManager);
    sessionId =
        transaction.execute(
            status -> {
              var asker = users.save(user("gate-asker-" + suffix, askerMatrixId));
              users.save(user(guestId, guestMatrixId));
              counsellor = consultants.save(consultant());
              colleague = consultants.save(consultant());
              var session =
                  new Session(asker, 1, "12345", 1L, Session.SessionStatus.IN_PROGRESS, false);
              session.setTenantId(7L);
              session.setMatrixRoomId(source);
              session.setLanguageCode(LanguageCode.de);
              session.setIsConsultantDirectlySet(false);
              session.setConsultant(counsellor);
              session.setCreateDate(LocalDateTime.now());
              session.setUpdateDate(LocalDateTime.now());
              session.setSupervisionOptedOut(false);
              return sessions.save(session).getId();
            });
    new JdbcTemplate(dataSource)
        .update(
            "INSERT INTO account_inactivity(identity_id,tenant_id,assigned_months,revision,"
                + "last_activity,due_at,status) VALUES (?,7,24,0,NOW(6),NOW(6),'ACTIVE')",
            guestId);
    long now = System.currentTimeMillis();
    String caller = counsellor.getMatrixUserId();
    // The counsellor's running call: counsellor, asker and (after admission) the guest are in it.
    var binding =
        MatrixCallBinding.builder()
            .sourceRoomId(source)
            .callId("call")
            .mediaRoomId(media)
            .callerMatrixId(caller)
            .sessionId(sessionId)
            .tenantId(7L)
            .invitedAt(now - 60_000)
            .inviteExpiresAt(now + 60_000)
            .startedAt(now - 50_000)
            .mediaObservedAt(now - 50_000)
            .video(true)
            .build();
    binding.getDevices().put("_" + caller + "_C", device(caller, now + 3_600_000));
    binding.getDevices().put("_" + askerMatrixId + "_A", device(askerMatrixId, now + 3_600_000));
    binding.getDevices().put("_" + guestMatrixId + "_G", device(guestMatrixId, now + 3_600_000));
    bindingId = transaction.execute(status -> bindings.save(binding).getId());

    when(accountLifecycle.snapshot(anyString())).thenReturn(Optional.empty());
    when(matrix.getCallRoomMembers(source)).thenReturn(Optional.of(List.of(caller, askerMatrixId)));
    when(matrix.getRoomMembers(source)).thenReturn(Optional.of(List.of(caller, askerMatrixId)));
    when(matrix.loginAsUserAccessToken(caller)).thenReturn("counsellor-token");
    when(matrix.removeUserFromRoom(media, guestMatrixId, "counsellor-token")).thenReturn(true);
    when(matrix.getRoomMembership(media, guestMatrixId, "counsellor-token"))
        .thenReturn(Optional.of("leave"));
    when(matrix.clearUserPowerLevel(media, guestMatrixId, "counsellor-token")).thenReturn(true);
    when(tenants.getRestrictedTenantDataFresh(7L))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(7L)
                .settings(
                    new Settings()
                        .featureCallsEnabled(true)
                        .featureAudioCallsEnabled(true)
                        .featureVideoCallsEnabled(true)));
    var hasher = new MatrixRtcCorrelationIdHasher();
    ReflectionTestUtils.setField(hasher, "secret", "test-hmac-secret");
    ReflectionTestUtils.invokeMethod(hasher, "init");
    gate =
        new MatrixRtcCallPolicyService(
            new MatrixRtcPolicyContextResolver(sessions, chats, supervisors, discussions),
            tenants,
            matrix,
            hasher,
            new JdbcTemplate(dataSource),
            callGuests);

    // The counsellor admits the guest into the running call (US#1477).
    assertThat(callGuests.admitAs(7L, counsellor.getId(), source, "call", guestId).outcome())
        .isEqualTo(MatrixCallGuestService.Outcome.ADMITTED);
    assertThat(admitted()).as("the admitted guest gets media").isTrue();
  }

  @Test
  void aReassignedSessionClosesTheGateAndTheSweepRevokesTheSeat() {
    new JdbcTemplate(dataSource)
        .update("UPDATE session SET consultant_id = ? WHERE id = ?", colleague.getId(), sessionId);

    assertThat(admitted()).as("authority lost: refused at once").isFalse();
    // No call-room event follows a reassignment: the reconcile sweep after the next sync finds it.
    reconciler.reconcileMediaRooms();

    assertSeatRevokedAndKept();
    assertThat(admitted()).isFalse();
  }

  @Test
  void aSuspendedCounsellorClosesTheGateAndTheLifecycleRevokesTheSeat() {
    when(accountLifecycle.snapshot(counsellor.getId())).thenReturn(Optional.of(suspended()));

    assertThat(admitted()).as("authority lost: refused at once").isFalse();
    assertThat(lifecycle.handleRoom(media, Map.of())).isTrue();

    assertSeatRevokedAndKept();
  }

  @Test
  void theCounsellorsExpiredMembershipClosesTheGateAndTheSeatGoesAfterTheGrace() {
    // The counsellor dropped out without hanging up: their membership ran out, the asker's and the
    // guest's are still live, so the binding itself stays live.
    long now = System.currentTimeMillis();
    expireCounsellorMembership(now - 5_000);

    assertThat(admitted()).as("no counsellor in the call: refused at once").isFalse();
    lifecycle.handleRoom(media, Map.of());
    assertThat(guests.findByBindingId(bindingId))
        .as("a brief reconnect keeps the seat during the grace window")
        .singleElement()
        .satisfies(seat -> assertThat(seat.getRevokedAt()).isNull());

    expireCounsellorMembership(now - MatrixCallGuestService.COUNSELLOR_ABSENCE_GRACE_MILLIS - 1);
    lifecycle.handleRoom(media, Map.of());

    assertSeatRevokedAndKept();
    assertThat(bindings.findById(bindingId).orElseThrow().getEndedAt())
        .as("the asker's membership keeps the call itself running")
        .isNull();
    assertThat(admitted()).isFalse();
  }

  @Test
  void theSweepRevokesAReassignedCallBehindMoreThanAPageOfHealthyCalls() {
    // Round 8 (F2): more running session calls with a seat than one sweep page, all healthy
    // (their assigned counsellor is in the call). Round 9 (F3): the reassigned call has the highest
    // id, so it sorts after all of them by b.id and is reached only if they are left out.
    healthyCallsWithASeat(MatrixCallGuestService.SWEEP_PAGE_SIZE + 1);
    lateCallWithTheHighestId();
    new JdbcTemplate(dataSource)
        .update("UPDATE session SET consultant_id = ? WHERE id = ?", colleague.getId(), sessionId);

    reconciler.reconcileMediaRooms();

    assertSeatRevokedAndKept(lateBindingId);
  }

  @Test
  void theSweepRetriesAFailedRevocationBehindMoreThanAPageOfHealthyCalls() {
    healthyCallsWithASeat(MatrixCallGuestService.SWEEP_PAGE_SIZE + 1);
    lateCallWithTheHighestId();
    new JdbcTemplate(dataSource)
        .update("UPDATE session SET consultant_id = ? WHERE id = ?", colleague.getId(), sessionId);
    // The first removal fails: the guest is still in the call room.
    when(matrix.removeUserFromRoom(lateMedia, guestMatrixId, "counsellor-token")).thenReturn(false);
    when(matrix.getRoomMembership(lateMedia, guestMatrixId, "counsellor-token"))
        .thenReturn(Optional.of("join"));
    lifecycle.handleRoom(lateMedia, Map.of());
    assertThat(guests.findByBindingId(lateBindingId))
        .singleElement()
        .satisfies(
            seat -> {
              assertThat(seat.getRevokedAt()).isNull();
              assertThat(seat.getLastRevokeAttemptAt()).isNotNull();
            });

    when(matrix.removeUserFromRoom(lateMedia, guestMatrixId, "counsellor-token")).thenReturn(true);
    when(matrix.getRoomMembership(lateMedia, guestMatrixId, "counsellor-token"))
        .thenReturn(Optional.of("leave"));
    reconciler.reconcileMediaRooms();

    assertSeatRevokedAndKept(lateBindingId);
  }

  @Test
  void theSweepReachesALessRecentlySweptCandidateBehindAFullPageOfCandidates() {
    // More candidates than one page whose seats stay (their counsellor dropped out seconds ago,
    // within the grace window). This test's reassigned call has the highest id and was swept once
    // long ago, so it sorts after the never-swept ones; the stamps of the first sweep put it first
    // in the second.
    callsWithASeat(MatrixCallGuestService.SWEEP_PAGE_SIZE + 1, System.currentTimeMillis() - 5_000);
    lateCallWithTheHighestId();
    new JdbcTemplate(dataSource)
        .update("UPDATE matrix_call_binding SET last_swept_at = 1 WHERE id = ?", lateBindingId);
    new JdbcTemplate(dataSource)
        .update("UPDATE session SET consultant_id = ? WHERE id = ?", colleague.getId(), sessionId);
    new JdbcTemplate(dataSource)
        .update("UPDATE matrix_call_binding SET last_swept_at = NULL WHERE id <> ?", lateBindingId);

    reconciler.reconcileMediaRooms();
    reconciler.reconcileMediaRooms();

    assertSeatRevokedAndKept(lateBindingId);
  }

  @Test
  void aDeletedCounsellorClosesTheGate() {
    new JdbcTemplate(dataSource)
        .update(
            "UPDATE consultant SET delete_date = NOW() WHERE consultant_id = ?",
            counsellor.getId());

    assertThat(admitted()).as("a deleted counsellor vouches for nobody").isFalse();
    reconciler.reconcileMediaRooms();

    assertSeatRevokedAndKept();
  }

  // Round 9 (F3): one test per predicate of the sweep's "rule holds" clause. The assigned
  // counsellor's membership ran out, so the call's only live counsellor is the colleague; with one
  // change to the colleague, the call must stay a candidate.

  @Test
  void aCallWithAnActiveColleagueOfTheTraegerInItIsNoCandidate() {
    liveColleagueOnly();

    assertThat(candidate()).as("control: the rule holds, the sweep leaves the call out").isFalse();
  }

  @Test
  void aColleagueOfAnotherTraegerKeepsTheCallACandidate() {
    liveColleagueOnly();
    new JdbcTemplate(dataSource)
        .update("UPDATE consultant SET tenant_id = 8 WHERE consultant_id = ?", colleague.getId());

    assertThat(candidate()).isTrue();
  }

  @Test
  void aSuspendedColleagueKeepsTheCallACandidate() {
    liveColleagueOnly();
    new JdbcTemplate(dataSource)
        .update(
            "INSERT INTO account_inactivity(identity_id,tenant_id,assigned_months,revision,"
                + "last_activity,due_at,status) VALUES (?,7,24,0,NOW(6),NOW(6),'SUSPENDED')",
            colleague.getId());

    assertThat(candidate()).isTrue();
  }

  @Test
  void aDeletedColleagueKeepsTheCallACandidate() {
    liveColleagueOnly();
    new JdbcTemplate(dataSource)
        .update(
            "UPDATE consultant SET delete_date = NOW() WHERE consultant_id = ?", colleague.getId());

    assertThat(candidate()).isTrue();
  }

  @Test
  void theCounsellorsExpiredMembershipKeepsTheCallACandidate() {
    expireCounsellorMembership(System.currentTimeMillis() - 5_000);

    assertThat(candidate()).isTrue();
  }

  @Test
  void theCounsellorsDepartureKeepsTheCallACandidate() {
    // A departure ends the membership at the departure's own timestamp.
    new JdbcTemplate(dataSource)
        .update(
            "UPDATE matrix_call_device SET expires_at = event_timestamp WHERE binding_id = ?"
                + " AND sender_matrix_id = ?",
            bindingId,
            counsellor.getMatrixUserId());

    assertThat(candidate()).isTrue();
  }

  @Test
  void theLiveKitEvictionRunsOnlyOnceTheRevokedSeatIsCommitted() throws Exception {
    // Round 9 (F1): the gate reads revoked_at without a lock; an eviction before the commit lets a
    // client fetch a new token at once. The stub reads the seat through its own connection.
    var revokedAtEviction = new java.util.concurrent.atomic.AtomicReference<Object>("not evicted");
    org.mockito.Mockito.doAnswer(
            invocation -> {
              revokedAtEviction.set(committedRevokedAt());
              return null;
            })
        .when(eviction)
        .evictFromCallRoom(media, guestMatrixId);
    new JdbcTemplate(dataSource)
        .update("UPDATE session SET consultant_id = ? WHERE id = ?", colleague.getId(), sessionId);

    assertThat(lifecycle.handleRoom(media, Map.of())).isTrue();

    assertSeatRevokedAndKept();
    assertThat(revokedAtEviction.get())
        .as("the eviction sees the revocation committed")
        .isNotNull()
        .isNotEqualTo("not evicted");
  }

  @Test
  void aModeratorRemovalEvictsOnlyAfterItsCommit() throws Exception {
    var revokedAtEviction = new java.util.concurrent.atomic.AtomicReference<Object>("not evicted");
    org.mockito.Mockito.doAnswer(
            invocation -> {
              revokedAtEviction.set(committedRevokedAt());
              return null;
            })
        .when(eviction)
        .evictFromCallRoom(media, guestMatrixId);

    assertThat(
            callGuests.revokeSeat(
                bindings.findById(bindingId).orElseThrow(),
                guestMatrixId,
                counsellor.getMatrixUserId()))
        .isTrue();

    assertThat(revokedAtEviction.get()).isNotNull().isNotEqualTo("not evicted");
  }

  @Test
  void theTakeoverPathEvictsOnlyOnceItsRevocationIsCommitted() throws Exception {
    // Round 10 (F2): the takeover's removal runs without a transaction, so the eviction ran at
    // once,
    // before the TransactionTemplate wrote revoked_at.
    var revokedAtEviction = new java.util.concurrent.atomic.AtomicReference<Object>("not evicted");
    org.mockito.Mockito.doAnswer(
            invocation -> {
              revokedAtEviction.set(committedRevokedAt());
              return null;
            })
        .when(eviction)
        .evictFromCallRoom(media, guestMatrixId);
    new JdbcTemplate(dataSource)
        .update("UPDATE session SET consultant_id = ? WHERE id = ?", colleague.getId(), sessionId);

    assertThat(callGuests.revokeBeforeTakeOver(bindings.findById(bindingId).orElseThrow()))
        .isTrue();

    assertSeatRevokedAndKept();
    assertThat(revokedAtEviction.get())
        .as("the eviction sees the revocation committed")
        .isNotNull()
        .isNotEqualTo("not evicted");
  }

  @Test
  void aRolledBackRevocationEvictsNobody() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              callGuests.revokeSeat(
                  bindings.findById(bindingId).orElseThrow(),
                  guestMatrixId,
                  counsellor.getMatrixUserId());
              status.setRollbackOnly();
            });

    org.mockito.Mockito.verify(eviction, org.mockito.Mockito.never())
        .evictFromCallRoom(anyString(), anyString());
    assertThat(guests.findByBindingId(bindingId))
        .singleElement()
        .satisfies(seat -> assertThat(seat.getRevokedAt()).isNull());
  }

  @Test
  void aRoomThatAlwaysFailsDoesNotStopTheSweepAndItsStampAdvances() {
    // Round 9 (F2): consultant.matrix_user_id is not unique. Two accounts of another Träger share
    // the Matrix id of a live member of this test's call; before round 10 every pass of it threw.
    // It sorts before the reassigned call; the sweep must still reach that one.
    String shared = "@gate-shared-" + UUID.randomUUID().toString().substring(0, 8) + ":example";
    var transaction = new TransactionTemplate(transactionManager);
    var twins =
        transaction.execute(
            status ->
                List.of(
                    consultants.save(consultantOfTenant(8L, shared)).getId(),
                    consultants.save(consultantOfTenant(8L, shared)).getId()));
    lateCallWithTheHighestId();
    addLiveDevice(bindingId, shared);
    expireCounsellorMembership(System.currentTimeMillis() - 5_000);
    new JdbcTemplate(dataSource)
        .update(
            "UPDATE matrix_call_binding SET last_swept_at = NULL WHERE id IN (?, ?)",
            bindingId,
            lateBindingId);
    // The late call lost its rule: its caller is not the session's assigned counsellor.
    new JdbcTemplate(dataSource)
        .update(
            "UPDATE matrix_call_binding SET caller_matrix_id = ? WHERE id = ?",
            colleague.getMatrixUserId(),
            lateBindingId);
    try {
      reconciler.reconcileMediaRooms();

      assertSeatRevokedAndKept(lateBindingId);
      assertThat(bindings.findById(bindingId).orElseThrow().getLastSweptAt())
          .as("the failing room is stamped, so it does not sort first forever")
          .isNotNull();
    } finally {
      for (String id : twins)
        new JdbcTemplate(dataSource)
            .update("UPDATE consultant SET delete_date = NOW() WHERE consultant_id = ?", id);
    }
  }

  @Test
  void aPoisonRoomInTheExpiryLoopDoesNotStopTheOtherRooms() {
    // Round 10 (F1): two not deleted accounts of this Träger share a Matrix id. Before, the
    // single-row lookup in the rule check threw for the poison room, the exception left the
    // reconcile pass and the sync never acknowledged its batch.
    var twins = poisonRoomThatRanOut();
    expireAllMemberships(bindingId);
    try {
      reconciler.reconcileMediaRooms();

      assertThat(bindings.findById(bindingId).orElseThrow().getEndedAt())
          .as("the expiry loop still reaches the call after the poison room")
          .isNotNull();
      assertSeatRevokedAndKept();
    } finally {
      softDelete(twins);
    }
  }

  @Test
  void aPoisonRoomOnTheLiveEventPathDoesNotStopTheBatchOrItsAcknowledgement() throws Exception {
    // Round 10 (F1): the same poison room, reached through a sync batch's joined rooms.
    var twins = poisonRoomThatRanOut();
    // The poison room keeps its authority, so its pass reaches the presence lookup.
    long before = System.currentTimeMillis();
    var cursor =
        mock(de.caritas.cob.userservice.api.service.matrix.MatrixEmailSyncCursorStore.class);
    var listener = listener(cursor);
    var joined = new java.util.LinkedHashMap<String, Object>();
    joined.put(poisonMedia, Map.of("timeline", Map.of("events", List.of())));
    joined.put(media, Map.of("timeline", Map.of("events", List.of())));
    when(matrix.makeMatrixRequest(
            anyString(),
            org.mockito.ArgumentMatchers.eq("GET"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(Map.of("next_batch", "batch-after-poison", "rooms", Map.of("join", joined)));
    try {
      org.assertj.core.api.Assertions.assertThatCode(
              () -> ReflectionTestUtils.invokeMethod(listener, "executeObservedMatrixSyncCycle"))
          .doesNotThrowAnyException();

      org.mockito.Mockito.verify(cursor).write("batch-after-poison");
      assertThat(bindings.findById(bindingId).orElseThrow().getMediaObservedAt())
          .as("the call room after the poison room is handled from this batch")
          .isGreaterThanOrEqualTo(before);
    } finally {
      softDelete(twins);
    }
  }

  private de.caritas.cob.userservice.api.service.matrix.MatrixEventListenerService listener(
      de.caritas.cob.userservice.api.service.matrix.MatrixEmailSyncCursorStore cursor) {
    return new de.caritas.cob.userservice.api.service.matrix.MatrixEventListenerService(
        matrix,
        mock(de.caritas.cob.userservice.api.service.session.SessionService.class),
        mock(
            de.caritas.cob.userservice.api.service.mobilepushmessage.MobilePushNotificationService
                .class),
        notifications,
        Optional.empty(),
        mock(UserRepository.class),
        mock(ConsultantRepository.class),
        mock(SessionRepository.class),
        mock(de.caritas.cob.userservice.api.service.statistics.ConsultantMessageStatService.class),
        mock(de.caritas.cob.userservice.api.service.statistics.CommunicationStatService.class),
        reconciler,
        mock(
            de.caritas.cob.userservice.api.service.notification.AdviceSeekerReplyEmailService
                .class),
        cursor,
        mock(de.caritas.cob.userservice.api.service.notification.InternalChatEmailService.class));
  }

  private void serveSyncBatch(String nextBatch, Map<String, Object> joined) {
    when(matrix.makeMatrixRequest(
            anyString(),
            org.mockito.ArgumentMatchers.eq("GET"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(Map.of("next_batch", nextBatch, "rooms", Map.of("join", joined)));
  }

  private static Object syncCycle(
      de.caritas.cob.userservice.api.service.matrix.MatrixEventListenerService listener) {
    return ReflectionTestUtils.invokeMethod(listener, "executeObservedMatrixSyncCycle");
  }

  private String poisonMedia;

  /**
   * A running call of this test's session whose memberships all ran out, held by the assigned
   * counsellor and by a Matrix id two accounts of the Träger share. Its conversation room does not
   * resolve, so its pass goes straight to the rule check. It sorts first by call-room id.
   */
  private List<String> poisonRoomThatRanOut() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String shared = "@gate-twin-" + suffix + ":example";
    poisonMedia = "!0-a-poison-" + suffix + ":example";
    long now = System.currentTimeMillis();
    String caller = counsellor.getMatrixUserId();
    return new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              var binding =
                  MatrixCallBinding.builder()
                      .sourceRoomId("!gate-unresolved-" + suffix + ":example")
                      .callId("poison-call")
                      .mediaRoomId(poisonMedia)
                      .callerMatrixId(caller)
                      .sessionId(sessionId)
                      .tenantId(7L)
                      .invitedAt(now - 60_000)
                      .inviteExpiresAt(now - 30_000)
                      .startedAt(now - 50_000)
                      .mediaObservedAt(now - 50_000)
                      .video(true)
                      .build();
              binding.getDevices().put("_" + caller + "_C", device(caller, now - 10_000));
              binding.getDevices().put("_" + shared + "_T", device(shared, now - 10_000));
              bindings.save(binding);
              return List.of(
                  consultants.save(consultantOfTenant(7L, shared)).getId(),
                  consultants.save(consultantOfTenant(7L, shared)).getId());
            });
  }

  private void expireAllMemberships(Long binding) {
    new JdbcTemplate(dataSource)
        .update(
            "UPDATE matrix_call_device SET expires_at = ? WHERE binding_id = ?",
            System.currentTimeMillis() - 10_000,
            binding);
  }

  private void softDelete(List<String> consultantIds) {
    for (String id : consultantIds)
      new JdbcTemplate(dataSource)
          .update("UPDATE consultant SET delete_date = NOW() WHERE consultant_id = ?", id);
  }

  @Test
  void aCallRoomWhoseLivePassKeepsFailingIsMarkedUnobservedAndNoLongerOpensToGuests()
      throws Exception {
    // Round 10 security scan: the batch whose pass keeps failing may carry the counsellor's
    // departure. Once the room no longer holds the batch, its memberships cannot be trusted: the
    // counsellor's stale membership must not keep the gate open or the seat.
    var failing = new java.util.concurrent.atomic.AtomicBoolean(true);
    String caller = counsellor.getMatrixUserId();
    when(matrix.getCallRoomMembers(source))
        .thenAnswer(
            ignored -> {
              if (failing.get())
                throw new org.springframework.dao.TransientDataAccessResourceException("down");
              return Optional.of(List.of(caller, guestMatrixId.replace("guest", "asker")));
            });
    var cursor =
        mock(de.caritas.cob.userservice.api.service.matrix.MatrixEmailSyncCursorStore.class);
    var listener = listener(cursor);
    serveSyncBatch(
        "batch-with-a-lost-departure",
        Map.of(media, Map.of("timeline", Map.of("events", List.of()))));

    for (int replay = 0; replay < MatrixCallInviteNotificationService.LIVE_ROOM_RETRIES; replay++)
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> syncCycle(listener))
          .isInstanceOf(MatrixCallInviteNotificationService.CallRoomBatchReplay.class);
    org.mockito.Mockito.verify(cursor, org.mockito.Mockito.never()).write(anyString());
    syncCycle(listener);
    org.mockito.Mockito.verify(cursor).write("batch-with-a-lost-departure");

    failing.set(false);
    assertThat(admitted()).as("stale presence keeps nothing open").isFalse();
    reconciler.reconcileMediaRooms();
    assertSeatRevokedAndKept();
  }

  @Test
  void anUnobservedRunningCallIsASweepCandidate() {
    new JdbcTemplate(dataSource)
        .update("UPDATE matrix_call_binding SET media_observed_at = NULL WHERE id = ?", bindingId);

    assertThat(candidate()).isTrue();
  }

  @Test
  void aLiveCounsellorWhoseMatrixIdIsAmbiguousKeepsTheCallACandidate() {
    var twin =
        new TransactionTemplate(transactionManager)
            .execute(
                status ->
                    consultants.save(consultantOfTenant(7L, counsellor.getMatrixUserId())).getId());
    try {
      assertThat(candidate()).isTrue();
    } finally {
      softDelete(List.of(twin));
    }
  }

  /** The assigned counsellor's membership ran out; an active colleague of the Träger is live. */
  private void liveColleagueOnly() {
    expireCounsellorMembership(System.currentTimeMillis() - 5_000);
    addLiveDevice(bindingId, colleague.getMatrixUserId());
  }

  private boolean candidate() {
    long now = System.currentTimeMillis();
    return guests
        .findRunningSessionMediaRoomsWhoseRuleMayHaveLapsed(
            now, now, org.springframework.data.domain.PageRequest.of(0, 100_000))
        .contains(media);
  }

  private void addLiveDevice(Long binding, String sender) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              var row = bindings.findById(binding).orElseThrow();
              row.getDevices()
                  .put("_" + sender + "_X", device(sender, System.currentTimeMillis() + 3_600_000));
              bindings.save(row);
            });
  }

  /** revoked_at of the guest's seat as committed, read through a connection of its own. */
  private Object committedRevokedAt() throws java.sql.SQLException {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT revoked_at FROM matrix_call_guest WHERE binding_id = ?"
                    + " AND matrix_user_id = ?")) {
      connection.setAutoCommit(true);
      statement.setLong(1, bindingId);
      statement.setString(2, guestMatrixId);
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getObject(1);
      }
    }
  }

  /**
   * A second running call of this test's session, created after every other binding of the test, so
   * it has the highest id. The counsellor and the asker are in it, and the guest holds a seat.
   */
  private void lateCallWithTheHighestId() {
    lateMedia = "!gate-late-" + UUID.randomUUID().toString().substring(0, 8) + ":example";
    long now = System.currentTimeMillis();
    String caller = counsellor.getMatrixUserId();
    var transaction = new TransactionTemplate(transactionManager);
    lateBindingId =
        transaction.execute(
            status -> {
              var binding =
                  MatrixCallBinding.builder()
                      .sourceRoomId(source)
                      .callId("late-call")
                      .mediaRoomId(lateMedia)
                      .callerMatrixId(caller)
                      .sessionId(sessionId)
                      .tenantId(7L)
                      .invitedAt(now - 60_000)
                      .inviteExpiresAt(now + 60_000)
                      .startedAt(now - 50_000)
                      .mediaObservedAt(now - 50_000)
                      .video(true)
                      .build();
              binding.getDevices().put("_" + caller + "_C", device(caller, now + 3_600_000));
              for (var device : bindings.findById(bindingId).orElseThrow().getDevices().values())
                if (!device.getSenderMatrixId().equals(caller)
                    && !device.getSenderMatrixId().equals(guestMatrixId))
                  binding
                      .getDevices()
                      .put(
                          "_" + device.getSenderMatrixId() + "_A",
                          device(device.getSenderMatrixId(), now + 3_600_000));
              Long id = bindings.save(binding).getId();
              guests.save(
                  de.caritas.cob.userservice.api.model.MatrixCallGuest.builder()
                      .bindingId(id)
                      .matrixUserId(guestMatrixId)
                      .admittedByMatrixId(caller)
                      .admittedAt(now - 40_000)
                      .build());
              return id;
            });
    assertThat(lateBindingId).isGreaterThan(bindingId);
    when(matrix.removeUserFromRoom(lateMedia, guestMatrixId, "counsellor-token")).thenReturn(true);
    when(matrix.getRoomMembership(lateMedia, guestMatrixId, "counsellor-token"))
        .thenReturn(Optional.of("leave"));
    when(matrix.clearUserPowerLevel(lateMedia, guestMatrixId, "counsellor-token")).thenReturn(true);
  }

  private void assertSeatRevokedAndKept(Long binding) {
    assertThat(guests.findByBindingId(binding))
        .as("revoked seats stay as records")
        .singleElement()
        .satisfies(seat -> assertThat(seat.getRevokedAt()).isNotNull());
  }

  /**
   * Running 1:1 calls of another session whose assigned counsellor is in the call, each holding an
   * unrevoked guest seat. They get higher binding ids than this test's first call, so a test that
   * needs a candidate behind them uses {@link #lateCallWithTheHighestId}.
   */
  private void healthyCallsWithASeat(int count) {
    callsWithASeat(count, System.currentTimeMillis() + 3_600_000);
  }

  /**
   * As {@link #healthyCallsWithASeat}, with the counsellor's membership ending at the given time.
   */
  private void callsWithASeat(int count, long counsellorExpiresAt) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    var transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(
        status -> {
          var asker =
              users.save(user("healthy-asker-" + suffix, "@healthy-asker-" + suffix + ":e"));
          var assigned = consultants.save(consultant());
          var session =
              new Session(asker, 1, "12345", 1L, Session.SessionStatus.IN_PROGRESS, false);
          session.setTenantId(7L);
          session.setMatrixRoomId("!healthy-source-" + suffix + ":example");
          session.setLanguageCode(LanguageCode.de);
          session.setIsConsultantDirectlySet(false);
          session.setConsultant(assigned);
          session.setCreateDate(LocalDateTime.now());
          session.setUpdateDate(LocalDateTime.now());
          session.setSupervisionOptedOut(false);
          Long healthySession = sessions.save(session).getId();
          long now = System.currentTimeMillis();
          String caller = assigned.getMatrixUserId();
          for (int index = 0; index < count; index++) {
            var binding =
                MatrixCallBinding.builder()
                    .sourceRoomId("!healthy-source-" + suffix + ":example")
                    .callId("call-" + index)
                    .mediaRoomId(String.format("!0-healthy-%s-%04d:example", suffix, index))
                    .callerMatrixId(caller)
                    .sessionId(healthySession)
                    .tenantId(7L)
                    .invitedAt(now - 60_000)
                    .inviteExpiresAt(now + 60_000)
                    .startedAt(now - 50_000)
                    .mediaObservedAt(now - 50_000)
                    .video(true)
                    .build();
            binding.getDevices().put("_" + caller + "_C", device(caller, counsellorExpiresAt));
            // The asker stays in the call, so the call itself keeps running.
            binding
                .getDevices()
                .put(
                    "_" + asker.getMatrixUserId() + "_A",
                    device(asker.getMatrixUserId(), now + 3_600_000));
            Long id = bindings.save(binding).getId();
            guests.save(
                de.caritas.cob.userservice.api.model.MatrixCallGuest.builder()
                    .bindingId(id)
                    .matrixUserId("@healthy-guest-" + index + ":example")
                    .admittedByMatrixId(caller)
                    .admittedAt(now - 40_000)
                    .build());
          }
        });
  }

  private boolean admitted() {
    return !gate.resolve(source, guestMatrixId, media).equals(CallMediaPolicy.denied());
  }

  private void assertSeatRevokedAndKept() {
    assertThat(guests.findByBindingId(bindingId))
        .as("revoked seats stay as records")
        .singleElement()
        .satisfies(seat -> assertThat(seat.getRevokedAt()).isNotNull());
  }

  private void expireCounsellorMembership(long expiresAt) {
    new JdbcTemplate(dataSource)
        .update(
            "UPDATE matrix_call_device SET expires_at = ? WHERE binding_id = ?"
                + " AND sender_matrix_id = ?",
            expiresAt,
            bindingId,
            counsellor.getMatrixUserId());
  }

  private AccountInactivityService.Snapshot suspended() {
    return new AccountInactivityService.Snapshot(
        counsellor.getId(),
        7L,
        1,
        1L,
        null,
        null,
        AccountInactivityService.Status.SUSPENDED,
        0,
        null);
  }

  private static MatrixCallDevice device(String sender, long expiresAt) {
    return new MatrixCallDevice(
        sender, System.currentTimeMillis() - 50_000, expiresAt, true, "$" + UUID.randomUUID());
  }

  private static User user(String id, String matrixId) {
    return User.builder()
        .userId(id)
        .username(id)
        .email(id + "@synthetic.oriso.test")
        .matrixUserId(matrixId)
        .tenantId(7L)
        .encourage2fa(true)
        .magicLinkLoginEnabled(false)
        .languageCode(LanguageCode.de)
        .createDate(LocalDateTime.now())
        .updateDate(LocalDateTime.now())
        .build();
  }

  private static Consultant consultantOfTenant(Long tenant, String matrixId) {
    var consultant = consultant();
    consultant.setTenantId(tenant);
    consultant.setMatrixUserId(matrixId);
    return consultant;
  }

  private static Consultant consultant() {
    var id = UUID.randomUUID().toString();
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setTenantId(7L);
    consultant.setCreateDate(LocalDateTime.now());
    consultant.setUpdateDate(LocalDateTime.now());
    consultant.setUsername("synthetic-" + id.substring(0, 8));
    consultant.setFirstName("Synthetic");
    consultant.setLastName("C" + id.substring(0, 8));
    consultant.setEmail(id.substring(0, 8) + "@synthetic.oriso.test");
    consultant.setAppointments(null);
    consultant.setConsultantAgencies(new HashSet<>());
    consultant.setConsultantMobileTokens(new HashSet<>());
    consultant.setEncourage2fa(true);
    consultant.setNotifyEnquiriesRepeating(true);
    consultant.setNotifyNewChatMessageFromAdviceSeeker(true);
    consultant.setWalkThroughEnabled(true);
    consultant.setTeamConsultant(false);
    consultant.setMagicLinkLoginEnabled(false);
    consultant.setLanguageCode(LanguageCode.de);
    consultant.setMatrixUserId("@synthetic-" + id.substring(0, 8) + ":synthetic.oriso.test");
    return consultant;
  }
}
