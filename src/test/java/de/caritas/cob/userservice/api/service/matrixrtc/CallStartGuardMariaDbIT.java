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
import de.caritas.cob.userservice.api.model.MatrixCallObservation;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallObservationRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.matrix.CounsellorStartedCall;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Frank, 17 Nov 2026, on MariaDB: only counsellors start calls; advice seekers and guests only join
 * a running call a counsellor of the Träger started. The media gate (the call policy the MatrixRTC
 * gateway asks before LiveKit issues a token) enforces it with the real records: call bindings,
 * call observations, consultants and guest seats. Only Matrix and the TenantService are simulated.
 */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  CounsellorStartedCall.class,
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
class CallStartGuardMariaDbIT {
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
    registry.add("matrix.calls.guest-revoke-retry-ms", () -> "0");
  }

  @Autowired private CounsellorStartedCall counsellorCalls;
  @Autowired private MatrixCallGuestService callGuests;
  @Autowired private MatrixCallBindingRepository bindings;
  @Autowired private MatrixCallObservationRepository observations;
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
  private String suffix;
  private String source;
  private String askerMatrixId;
  private String guestMatrixId;
  private String guestId;
  private String foreignMatrixId;
  private Consultant counsellor;
  private Long sessionId;

  @BeforeEach
  void setUp() {
    suffix = UUID.randomUUID().toString().substring(0, 8);
    source = "!start-source-" + suffix + ":example";
    askerMatrixId = "@start-asker-" + suffix + ":example";
    guestMatrixId = "@start-guest-" + suffix + ":example";
    guestId = "start-guest-" + suffix;
    foreignMatrixId = "@start-foreign-" + suffix + ":example";
    String askerId = "start-asker-" + suffix;
    var transaction = new TransactionTemplate(transactionManager);
    sessionId =
        transaction.execute(
            status -> {
              var asker = users.save(user(askerId, askerMatrixId));
              users.save(user(guestId, guestMatrixId));
              counsellor = consultants.save(consultant(7L, null));
              consultants.save(consultant(8L, foreignMatrixId));
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
    for (String identity :
        List.of(askerId, guestId, counsellor.getId(), foreignConsultantId(foreignMatrixId)))
      new JdbcTemplate(dataSource)
          .update(
              "INSERT INTO account_inactivity(identity_id,tenant_id,assigned_months,revision,"
                  + "last_activity,due_at,status) VALUES (?,7,24,0,NOW(6),NOW(6),'ACTIVE')",
              identity);

    when(accountLifecycle.snapshot(anyString())).thenReturn(Optional.empty());
    var members = List.of(counsellor.getMatrixUserId(), askerMatrixId, foreignMatrixId);
    when(matrix.getCallRoomMembers(source)).thenReturn(Optional.of(members));
    when(matrix.getRoomMembers(source)).thenReturn(Optional.of(members));
    when(matrix.loginAsUserAccessToken(counsellor.getMatrixUserId()))
        .thenReturn("counsellor-token");
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
            callGuests,
            counsellorCalls);
  }

  @Test
  void theAskerCannotStartACallInTheirOwnCallRoom() {
    String askersRoom = callOf(askerMatrixId, "asker-call");

    assertThat(gate.resolve(source, askerMatrixId, askersRoom))
        .isEqualTo(CallMediaPolicy.refused(CallMediaPolicy.CALL_START_REQUIRES_COUNSELLOR));
  }

  @Test
  void theAskerCannotStartACallInARoomTheServerHasNoRecordOf() {
    assertThat(gate.resolve(source, askerMatrixId, "!fresh-" + suffix + ":example"))
        .isEqualTo(CallMediaPolicy.refused(CallMediaPolicy.CALL_START_REQUIRES_COUNSELLOR));
  }

  @Test
  void theAskerJoinsTheCounsellorsRunningCall() {
    String room = callOf(counsellor.getMatrixUserId(), "counsellor-call");

    assertThat(gate.resolve(source, askerMatrixId, room))
        .isEqualTo(new CallMediaPolicy(true, true));
  }

  @Test
  void theAskerJoinsTheCounsellorsEncryptedCallKnownThroughItsObservation() {
    String room = observedCallOf(counsellor.getMatrixUserId(), "observed-call");

    assertThat(gate.resolve(source, askerMatrixId, room))
        .isEqualTo(new CallMediaPolicy(true, true));
  }

  @Test
  void theAskersOwnObservedCallStaysAStart() {
    String room = observedCallOf(askerMatrixId, "asker-observed-call");

    assertThat(gate.resolve(source, askerMatrixId, room).denialReason())
        .isEqualTo(CallMediaPolicy.CALL_START_REQUIRES_COUNSELLOR);
  }

  @Test
  void theAskerCannotReopenTheCounsellorsEndedCall() {
    String room = callOf(counsellor.getMatrixUserId(), "ended-call");
    new JdbcTemplate(dataSource)
        .update(
            "UPDATE matrix_call_binding SET ended_at = ? WHERE media_room_id = ?",
            System.currentTimeMillis() - 1,
            room);

    assertThat(gate.resolve(source, askerMatrixId, room).denialReason())
        .isEqualTo(CallMediaPolicy.CALL_START_REQUIRES_COUNSELLOR);
  }

  @Test
  void theCounsellorStartsACall() {
    assertThat(gate.resolve(source, counsellor.getMatrixUserId(), "!new-" + suffix + ":example"))
        .isEqualTo(new CallMediaPolicy(true, true));
  }

  @Test
  void aCounsellorOfAnotherTraegerCannotStartACall() {
    assertThat(gate.resolve(source, foreignMatrixId, "!new-" + suffix + ":example").denialReason())
        .isEqualTo(CallMediaPolicy.CALL_START_REQUIRES_COUNSELLOR);
  }

  @Test
  void aCallOfACounsellorOfAnotherTraegerOpensNothingToTheAsker() {
    String room = callOf(foreignMatrixId, "foreign-call");

    assertThat(gate.resolve(source, askerMatrixId, room).denialReason())
        .isEqualTo(CallMediaPolicy.CALL_START_REQUIRES_COUNSELLOR);
  }

  /** The guest a counsellor let in (share link, knock or direct admission) joins that call. */
  @Test
  void anAdmittedGuestJoinsTheCounsellorsRunningCall() {
    String room = callOf(counsellor.getMatrixUserId(), "call");

    assertThat(gate.resolve(source, guestMatrixId, room))
        .as("before admission")
        .isEqualTo(CallMediaPolicy.denied());
    assertThat(callGuests.admitAs(7L, counsellor.getId(), source, "call", guestId).outcome())
        .isEqualTo(MatrixCallGuestService.Outcome.ADMITTED);
    assertThat(gate.resolve(source, guestMatrixId, room))
        .isEqualTo(new CallMediaPolicy(true, true));
  }

  /**
   * The check only reads. While the lifecycle holds the binding (lock order: binding, session,
   * observation and end, link, knocks), the gate answers from the committed state at once instead
   * of waiting, so it can never deadlock with them; once the end commits, the call is no longer
   * joinable.
   */
  @Test
  void theGateTakesNoLockAndSeesTheEndOnceItCommits() throws Exception {
    String room = callOf(counsellor.getMatrixUserId(), "locked-call");
    Long id = bindings.findByMediaRoomId(room).orElseThrow().getId();
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> lifecycle =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .executeWithoutResult(
                          status -> {
                            bindings.lockById(id).orElseThrow();
                            new JdbcTemplate(dataSource)
                                .update(
                                    "UPDATE matrix_call_binding SET ended_at = ? WHERE id = ?",
                                    System.currentTimeMillis(),
                                    id);
                            locked.countDown();
                            await(release);
                          }));
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

      long started = System.nanoTime();
      var whileLocked = gate.resolve(source, askerMatrixId, room);
      long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

      assertThat(whileLocked)
          .as("the end is not committed yet")
          .isEqualTo(new CallMediaPolicy(true, true));
      assertThat(waitedMillis).as("no wait on the binding lock").isLessThan(5_000);

      release.countDown();
      lifecycle.get(10, TimeUnit.SECONDS);
      assertThat(gate.resolve(source, askerMatrixId, room).denialReason())
          .isEqualTo(CallMediaPolicy.CALL_START_REQUIRES_COUNSELLOR);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  /**
   * Asker and counsellor press "call" at the same moment: each makes a call room of their own. The
   * asker never gets media for theirs, whichever request comes first; both can be in the
   * counsellor's.
   */
  @Test
  void whenAskerAndCounsellorStartAtOnceOnlyTheCounsellorsCallCarriesMedia() throws Exception {
    String askersRoom = callOf(askerMatrixId, "asker-race");
    String counsellorsRoom = callOf(counsellor.getMatrixUserId(), "counsellor-race");
    var executor = Executors.newFixedThreadPool(4);
    try {
      var start = new CountDownLatch(1);
      List<Future<CallMediaPolicy>> askerOwn = new ArrayList<>();
      List<Future<CallMediaPolicy>> askerJoin = new ArrayList<>();
      List<Future<CallMediaPolicy>> counsellorOwn = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        askerOwn.add(executor.submit(() -> after(start, source, askerMatrixId, askersRoom)));
        askerJoin.add(executor.submit(() -> after(start, source, askerMatrixId, counsellorsRoom)));
        counsellorOwn.add(
            executor.submit(
                () -> after(start, source, counsellor.getMatrixUserId(), counsellorsRoom)));
      }
      start.countDown();
      for (var answer : askerOwn)
        assertThat(answer.get(30, TimeUnit.SECONDS).denialReason())
            .isEqualTo(CallMediaPolicy.CALL_START_REQUIRES_COUNSELLOR);
      for (var answer : askerJoin)
        assertThat(answer.get(30, TimeUnit.SECONDS)).isEqualTo(new CallMediaPolicy(true, true));
      for (var answer : counsellorOwn)
        assertThat(answer.get(30, TimeUnit.SECONDS)).isEqualTo(new CallMediaPolicy(true, true));
    } finally {
      executor.shutdownNow();
    }
  }

  private CallMediaPolicy after(CountDownLatch start, String room, String who, String callRoom) {
    await(start);
    return gate.resolve(room, who, callRoom);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("latch timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  /** A running, observed call whose caller is {@code caller}, with the caller in it. */
  private String callOf(String caller, String callId) {
    String media = "!start-media-" + callId + "-" + suffix + ":example";
    long now = System.currentTimeMillis();
    var binding =
        MatrixCallBinding.builder()
            .sourceRoomId(source)
            .callId(callId)
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
    binding.getDevices().put("_" + caller + "_D", device(caller, now + 3_600_000));
    new TransactionTemplate(transactionManager).executeWithoutResult(s -> bindings.save(binding));
    return media;
  }

  /** An encrypted call known only through its creator's verified registration. */
  private String observedCallOf(String creator, String callId) {
    String media = "!start-observed-" + callId + "-" + suffix + ":example";
    long now = System.currentTimeMillis();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            s ->
                observations.save(
                    MatrixCallObservation.builder()
                        .origin(MatrixCallObservation.ORIGIN_CREATOR_REGISTRATION)
                        .sourceRoomId(source)
                        .callId(callId)
                        .mediaRoomId(media)
                        .creatorMatrixId(creator)
                        .tenantId(7L)
                        .sessionId(sessionId)
                        .video(true)
                        .registeredAt(now - 10_000)
                        .lastSeenAt(now - 10_000)
                        .expiresAt(now + 600_000)
                        .build()));
    return media;
  }

  private String foreignConsultantId(String matrixId) {
    return consultants.findAllByMatrixUserIdAndDeleteDateIsNull(matrixId).getFirst().getId();
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

  private static Consultant consultant(Long tenant, String matrixId) {
    var id = UUID.randomUUID().toString();
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setTenantId(tenant);
    consultant.setCreateDate(LocalDateTime.now());
    consultant.setUpdateDate(LocalDateTime.now());
    consultant.setUsername("synthetic-" + id.substring(0, 8));
    consultant.setFirstName("Synthetic");
    consultant.setLastName("C" + id.substring(0, 8));
    consultant.setEmail(id.substring(0, 8) + "@synthetic.oriso.test");
    consultant.setConsultantAgencies(new HashSet<>());
    consultant.setConsultantMobileTokens(new HashSet<>());
    consultant.setEncourage2fa(true);
    consultant.setNotifyEnquiriesRepeating(true);
    consultant.setNotifyNewChatMessageFromAdviceSeeker(true);
    consultant.setWalkThroughEnabled(true);
    consultant.setTeamConsultant(false);
    consultant.setMagicLinkLoginEnabled(false);
    consultant.setLanguageCode(LanguageCode.de);
    consultant.setMatrixUserId(
        matrixId != null ? matrixId : "@synthetic-" + id.substring(0, 8) + ":synthetic.oriso.test");
    return consultant;
  }
}
