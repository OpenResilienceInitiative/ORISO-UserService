package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.MatrixCallDevice;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatJoinRequestRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallGuestRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.EffectiveAgencySettingsLookup;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CallShareLinkEndedException;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CallShareLinkService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CallShareLinkSweep;
import de.caritas.cob.userservice.api.service.agencyinvitelink.ConfiguredTemporaryAccountPolicy;
import de.caritas.cob.userservice.api.service.agencyinvitelink.DirectCallKnockService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.EffectiveDirectInvitePolicy;
import de.caritas.cob.userservice.api.service.notification.EventNotificationDeduplicationWriter;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;

/**
 * FE#2209 round 4 on MariaDB at REPEATABLE READ, guest paths racing session-call writes.
 *
 * <p>A guest's join that read the link and the binding before the call ended must not seat the
 * guest once the end commits. The join reads unlocked (snapshot and Hibernate entities fixed), then
 * waits on the binding row the end holds. With the stale entity it would seat the guest in the
 * ended call; it has to see the end and refuse.
 *
 * <p>Admit-all for a conversation whose call has no binding row yet must not deadlock with the
 * first bind of that call: bind holds the session and inserts the binding; admit-all must not hold
 * a range or gap lock on the call bindings while it waits for the session.
 */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  SessionCallAuthority.class,
  EventNotificationService.class,
  ConsultantDisplayNameResolver.class,
  EventNotificationDeduplicationWriter.class,
  MatrixCallLifecycleService.class,
  MatrixCallConversationResolver.class,
  MatrixCallGuestService.class,
  CallShareLinkService.class,
  CallShareLinkSweep.class,
  DirectCallKnockService.class,
  EffectiveDirectInvitePolicy.class,
  ConfiguredTemporaryAccountPolicy.class,
  MatrixSessionCallBindingService.class,
  MatrixCallBindingService.class,
  MatrixCallBindingWriter.class,
  OrisoRoomRegistry.class,
  MatrixCallObservationService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CallShareLinkRaceMariaDbIT {
  private static final MariaDBContainer<?> DATABASE =
      new MariaDBContainer<>("mariadb:10.11").withDatabaseName("userservice");

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
    // Deployment isolation level (nothing sets one, so MariaDB's default), pinned and asserted.
    registry.add(
        "spring.datasource.hikari.transaction-isolation", () -> "TRANSACTION_REPEATABLE_READ");
    registry.add(
        "spring.datasource.hikari.connection-init-sql",
        () -> "SET SESSION innodb_lock_wait_timeout = 15");
  }

  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;
  @Autowired private SessionRepository sessions;
  @Autowired private MatrixCallBindingRepository bindings;
  @Autowired private MatrixCallGuestRepository guests;
  @Autowired private AgencyInviteLinkRepository links;
  @Autowired private GroupChatJoinRequestRepository knockRows;
  @Autowired private CallShareLinkService shareLinks;
  @Autowired private MatrixCallObservationService observations;
  @Autowired private DirectCallKnockService knocks;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private javax.sql.DataSource dataSource;

  @MockitoSpyBean private MatrixSessionCallBindingService sessionBindings;
  @MockitoBean private MatrixSynapseService matrix;
  @MockitoBean private AccountInactivityService accountLifecycle;
  @MockitoBean private MatrixFeedUpdateSignalService feedUpdateSignals;
  @MockitoBean private IdentityTombstoneService tombstones;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private EffectiveAgencySettingsLookup effectiveAgencySettings;

  @MockitoBean
  private de.caritas.cob.userservice.api.service.chat.GroupWaitingRoomService waitingRooms;

  private TransactionTemplate transaction;
  private String suffix;
  private String source;
  private String media;
  private String guestMatrixId;
  private Consultant counsellor;
  private Long sessionId;

  @BeforeEach
  void setUp() {
    transaction = new TransactionTemplate(transactionManager);
    suffix = UUID.randomUUID().toString().substring(0, 8);
    source = "!join-end-source-" + suffix + ":example";
    media = "!join-end-media-" + suffix + ":example";
    String askerMatrixId = "@join-end-asker-" + suffix + ":example";
    guestMatrixId = "@join-end-guest-" + suffix + ":example";
    sessionId =
        transaction.execute(
            status -> {
              var owner = users.save(user("join-end-asker-" + suffix, askerMatrixId));
              users.save(user("join-end-guest-" + suffix, guestMatrixId));
              counsellor = consultants.save(consultant());
              var conversation =
                  new Session(owner, 1, "12345", 1L, Session.SessionStatus.IN_PROGRESS, false);
              conversation.setTenantId(7L);
              conversation.setAgencyId(99L);
              conversation.setMatrixRoomId(source);
              conversation.setLanguageCode(LanguageCode.de);
              conversation.setIsConsultantDirectlySet(false);
              conversation.setConsultant(counsellor);
              conversation.setCreateDate(LocalDateTime.now());
              conversation.setUpdateDate(LocalDateTime.now());
              conversation.setSupervisionOptedOut(false);
              return sessions.save(conversation).getId();
            });
    long now = System.currentTimeMillis();
    var running =
        MatrixCallBinding.builder()
            .sourceRoomId(source)
            .callId("call")
            .mediaRoomId(media)
            .callerMatrixId(counsellor.getMatrixUserId())
            .sessionId(sessionId)
            .tenantId(7L)
            .invitedAt(now - 1000)
            .inviteExpiresAt(now + 60000)
            .startedAt(now - 900)
            .mediaObservedAt(now - 900)
            .video(false)
            .build();
    running
        .getDevices()
        .put(
            "_" + counsellor.getMatrixUserId() + "_device",
            new MatrixCallDevice(
                counsellor.getMatrixUserId(), now - 900, now + 600000, true, "$start"));
    transaction.executeWithoutResult(status -> bindings.save(running));
    when(accountLifecycle.snapshot(anyString())).thenReturn(Optional.empty());
    when(tenantService.getRestrictedTenantDataFresh(7L))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(7L)
                .settings(
                    new Settings()
                        .featureDirectInviteChatEnabled(true)
                        .featureDirectInviteAudioEnabled(true)
                        .featureDirectInviteVideoEnabled(true)));
    when(effectiveAgencySettings.findEffectiveSettings(anyLong(), anyLong()))
        .thenReturn(
            Optional.of(
                new de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings()
                    .featureDirectInviteChatEnabled(true)
                    .featureDirectInviteAudioEnabled(true)
                    .featureDirectInviteVideoEnabled(true)));
    var members = List.of(askerMatrixId, counsellor.getMatrixUserId());
    when(matrix.getCallRoomMembers(source)).thenReturn(Optional.of(members));
    when(matrix.getRoomMembers(source)).thenReturn(Optional.of(members));
    when(matrix.loginAsUserAccessToken(counsellor.getMatrixUserId())).thenReturn("c-token");
    when(matrix.getRoomMembership(eq(media), anyString(), eq("c-token")))
        .thenReturn(Optional.of("leave"));
    when(matrix.removeUserFromRoom(eq(media), anyString(), eq("c-token"))).thenReturn(true);
    when(matrix.clearUserPowerLevel(eq(media), anyString(), eq("c-token"))).thenReturn(true);
  }

  @AfterEach
  void tearDown() {
    var cleanup = new JdbcTemplate(dataSource);
    cleanup.update("DELETE FROM matrix_call_observation_end WHERE source_room_id = ?", source);
    cleanup.update("DELETE FROM matrix_call_observation WHERE source_room_id = ?", source);
    transaction.executeWithoutResult(
        status -> {
          knockRows.deleteAll();
          links.deleteAll();
          guests.deleteAll();
          bindings.deleteAll();
          sessions.deleteById(sessionId);
          users.deleteById("join-end-asker-" + suffix);
          users.deleteById("join-end-guest-" + suffix);
          consultants.deleteById(counsellor.getId());
        });
  }

  @Test
  void runsAtTheDeploymentsIsolationLevel() {
    assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT @@tx_isolation", String.class))
        .isEqualTo("REPEATABLE-READ");
  }

  @Test
  void aJoinWaitingOnTheEndSeatsNobodyOnceTheEndCommits() throws Exception {
    String token =
        shareLinks.create(7L, counsellor.getId(), source, "call", false, null).link().getToken();
    var guest = users.findById("join-end-guest-" + suffix).orElseThrow();
    var actor = new AuthenticatedUser();
    actor.setUserId(counsellor.getId());
    actor.setTenantId(7L);
    actor.setRoles(Set.of("consultant"));

    var endHoldsTheBinding = new CountDownLatch(1);
    var releaseEnd = new CountDownLatch(1);
    // The end pauses inside its transaction, right after it ended the binding (row locked).
    doAnswer(
            invocation -> {
              Object room = invocation.callRealMethod();
              endHoldsTheBinding.countDown();
              releaseEnd.await(30, TimeUnit.SECONDS);
              return room;
            })
        .when(sessionBindings)
        .endWhileSessionLocked(any(), any(), eq("call"), anyLong(), any());

    var pool = Executors.newFixedThreadPool(2);
    var joinThread = new AtomicReference<Thread>();
    try (var connections =
        MariaDbLockWaits.TransactionConnections.watch(transactionManager, dataSource)) {
      var ended = pool.submit(() -> observations.end(actor, source, "call"));
      assertThat(endHoldsTheBinding.await(15, TimeUnit.SECONDS)).isTrue();
      var joined =
          pool.submit(
              () -> {
                joinThread.set(Thread.currentThread());
                return shareLinks.join(token, guest);
              });
      // The join read the link and the (still running) call unlocked, and now waits on the
      // binding row the end holds.
      MariaDbLockWaits.awaitLockWait(DATABASE, connections.of(joinThread), "matrix_call_binding");
      releaseEnd.countDown();
      assertThat(ended.get(30, TimeUnit.SECONDS))
          .isEqualTo(MatrixCallObservationService.Outcome.OK);
      Throwable refusal = null;
      Object joinResult = null;
      try {
        joinResult = joined.get(30, TimeUnit.SECONDS);
      } catch (ExecutionException failure) {
        refusal = failure.getCause();
      }
      assertThat(refusal)
          .as("the join sees the committed end, but returned %s", joinResult)
          .isInstanceOf(CallShareLinkEndedException.class);
    } finally {
      releaseEnd.countDown();
      pool.shutdownNow();
    }
    var binding = bindings.findBySourceRoomIdAndCallId(source, "call").orElseThrow();
    assertThat(binding.getEndedAt()).isNotNull();
    assertThat(guests.findByBindingId(binding.getId())).isEmpty();
    verify(matrix, never()).inviteUserToRoom(eq(media), eq(guestMatrixId), anyString());
  }

  @Test
  void admitAllDuringTheFirstBindOfACallWaitsInsteadOfDeadlocking() throws Exception {
    // The conversation has no call binding yet: bind will insert the first one.
    transaction.executeWithoutResult(status -> bindings.deleteAll());
    String newMedia = "!join-end-new-media-" + suffix + ":example";
    String caller = counsellor.getMatrixUserId();
    when(matrix.getCallRoomBinding(newMedia, caller))
        .thenReturn(
            Optional.of(java.util.Map.of("call_id", "first-call", "source_room_id", source)));
    when(matrix.getCallRoomOrigin(newMedia, caller))
        .thenReturn(Optional.of(new MatrixSynapseService.CallRoomOrigin(caller, caller)));
    when(matrix.ensureAdminInRoom(anyString(), anyString())).thenReturn(true);
    var actor = new AuthenticatedUser();
    actor.setUserId(counsellor.getId());
    actor.setTenantId(7L);
    actor.setRoles(Set.of("consultant"));
    long deadlocksBefore = deadlocks();

    var bindHoldsTheSession = new CountDownLatch(1);
    var releaseBind = new CountDownLatch(1);
    var bindThread = new AtomicReference<Thread>();
    var paused = new java.util.concurrent.atomic.AtomicBoolean();
    // The first account check inside bind's write transaction: it holds the session lock and has
    // not inserted the binding yet.
    when(accountLifecycle.snapshot(counsellor.getId()))
        .thenAnswer(
            invocation -> {
              if (Thread.currentThread() == bindThread.get()
                  && org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive()
                  && paused.compareAndSet(false, true)) {
                bindHoldsTheSession.countDown();
                releaseBind.await(30, TimeUnit.SECONDS);
              }
              return Optional.empty();
            });

    var pool = Executors.newFixedThreadPool(2);
    var admitThread = new AtomicReference<Thread>();
    try (var connections =
        MariaDbLockWaits.TransactionConnections.watch(transactionManager, dataSource)) {
      var bound =
          pool.submit(
              () -> {
                bindThread.set(Thread.currentThread());
                return sessionBindings.bind(actor, source, "first-call", newMedia, true);
              });
      assertThat(bindHoldsTheSession.await(15, TimeUnit.SECONDS)).isTrue();
      var admitted =
          pool.submit(
              () -> {
                admitThread.set(Thread.currentThread());
                return knocks.admitAll(
                    consultants.findById(counsellor.getId()).orElseThrow(), null, source);
              });
      // Admit-all's own connection waits on the session row bind holds.
      MariaDbLockWaits.awaitLockWait(DATABASE, connections.of(admitThread), "session");
      releaseBind.countDown();
      assertThat(bound.get(30, TimeUnit.SECONDS)).isTrue();
      assertThat(admitted.get(30, TimeUnit.SECONDS)).isEmpty();
    } finally {
      releaseBind.countDown();
      pool.shutdownNow();
    }
    assertThat(deadlocks()).as("InnoDB deadlocks").isEqualTo(deadlocksBefore);
    assertThat(bindings.findBySourceRoomIdAndCallId(source, "first-call")).isPresent();
  }

  private long deadlocks() {
    return Long.parseLong(
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT VARIABLE_VALUE FROM information_schema.GLOBAL_STATUS"
                    + " WHERE VARIABLE_NAME = 'INNODB_DEADLOCKS'",
                String.class));
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
