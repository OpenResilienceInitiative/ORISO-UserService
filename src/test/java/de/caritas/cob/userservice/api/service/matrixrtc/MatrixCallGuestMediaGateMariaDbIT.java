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
