package de.caritas.cob.userservice.api.service.matrixrtc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.controller.MatrixCallStateController;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
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
import de.caritas.cob.userservice.api.service.matrix.MatrixCallConversationResolver;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallLifecycleService;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallStateService;
import de.caritas.cob.userservice.api.service.matrix.MatrixFeedUpdateSignalService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationDeduplicationWriter;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ORISO-UserService#1477: a call guest is invited into the call room only, never into the source
 * conversation, and loses the call room when the call ends. Real repositories and schema; only the
 * Matrix homeserver and the TenantService are simulated.
 */
@DataJpaTest
@TestPropertySource(
    properties = {
      "spring.profiles.active=testing,call-guest-account-fixture",
      // Retry at once: these tests replay the recovery without waiting for the backoff.
      "matrix.calls.guest-revoke-retry-ms=0"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  EventNotificationService.class,
  ConsultantDisplayNameResolver.class,
  EventNotificationDeduplicationWriter.class,
  MatrixCallLifecycleService.class,
  MatrixCallConversationResolver.class,
  MatrixCallStateService.class,
  MatrixCallGuestService.class,
  de.caritas.cob.userservice.api.service.matrix.MatrixCallBindingService.class,
  de.caritas.cob.userservice.api.service.matrix.MatrixCallBindingWriter.class,
  de.caritas.cob.userservice.api.service.matrix.MatrixCallInviteNotificationService.class,
  MatrixCallGuestRoomIT.AccountFixture.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MatrixCallGuestRoomIT {
  // Profile-gated: a component-scanning @SpringBootTest would otherwise pick up this second bean.
  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  @org.springframework.context.annotation.Profile("call-guest-account-fixture")
  static class AccountFixture {
    @org.springframework.context.annotation.Bean
    AccountInactivityService accountLifecycle(
        javax.sql.DataSource dataSource, PlatformTransactionManager transactionManager) {
      return new AccountInactivityService(
          new JdbcTemplate(dataSource),
          transactionManager,
          java.time.Clock.systemUTC(),
          mock(
              de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffects
                  .class));
    }
  }

  private static final String SOURCE = "!guest-source:example";
  private static final String MEDIA = "!guest-media:example";
  private static final String ASKER = "@guest-asker:example";
  private static final String GUEST = "@call-guest:example";

  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;
  @Autowired private SessionRepository sessions;
  @Autowired private ChatRepository chats;
  @Autowired private SessionSupervisorRepository supervisors;
  @Autowired private TeamDiscussionRepository discussions;
  @Autowired private MatrixCallBindingRepository bindings;
  @Autowired private MatrixCallGuestRepository guests;
  @Autowired private MatrixCallLifecycleService lifecycle;
  @Autowired private MatrixCallStateService callStates;
  @Autowired private MatrixCallGuestService callGuests;

  @Autowired
  private de.caritas.cob.userservice.api.service.matrix.MatrixCallInviteNotificationService
      callReconciler;

  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private javax.sql.DataSource dataSource;
  @MockitoBean private MatrixSynapseService matrix;
  @MockitoBean private MatrixFeedUpdateSignalService feedUpdateSignals;
  @MockitoBean private IdentityTombstoneService tombstones;

  private TransactionTemplate transaction;
  private String counsellorId;
  private String counsellorMatrixId;
  private Long sessionId;

  @BeforeEach
  void setUp() {
    transaction = new TransactionTemplate(transactionManager);
    var jdbc = new JdbcTemplate(dataSource);
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS account_inactivity(identity_id VARCHAR(36) PRIMARY KEY,"
            + "tenant_id BIGINT,assigned_months INT NOT NULL,revision BIGINT NOT NULL,"
            + "last_activity TIMESTAMP(6) NOT NULL,due_at TIMESTAMP(6) NOT NULL,"
            + "status VARCHAR(20) NOT NULL,last_error VARCHAR(1000),attempts INT DEFAULT 0 NOT NULL)");
    jdbc.update("DELETE FROM account_inactivity");
    sessionId =
        transaction.execute(
            status -> {
              var owner = users.save(user("guest-asker", ASKER));
              users.save(user("call-guest", GUEST));
              var counsellor = consultants.save(consultant());
              counsellorId = counsellor.getId();
              counsellorMatrixId = counsellor.getMatrixUserId();
              var conversation =
                  new Session(owner, 1, "12345", 1L, Session.SessionStatus.IN_PROGRESS, false);
              conversation.setTenantId(7L);
              conversation.setMatrixRoomId(SOURCE);
              conversation.setLanguageCode(LanguageCode.de);
              conversation.setIsConsultantDirectlySet(false);
              conversation.setConsultant(counsellor);
              return sessions.save(conversation).getId();
            });
    for (String identity : List.of("guest-asker", "call-guest", counsellorId)) {
      jdbc.update(
          "INSERT INTO account_inactivity(identity_id,tenant_id,assigned_months,revision,"
              + "last_activity,due_at,status) VALUES (?,7,12,1,CURRENT_TIMESTAMP,"
              + "CURRENT_TIMESTAMP,'ACTIVE')",
          identity);
    }
    long now = System.currentTimeMillis();
    var running =
        MatrixCallBinding.builder()
            .sourceRoomId(SOURCE)
            .callId("guest-call")
            .mediaRoomId(MEDIA)
            .callerMatrixId(counsellorMatrixId)
            .sessionId(sessionId)
            .tenantId(7L)
            .invitedAt(now - 1000)
            .inviteExpiresAt(now + 60000)
            .startedAt(now - 900)
            .mediaObservedAt(now - 900)
            .video(true)
            .build();
    running
        .getDevices()
        .put(
            "_" + counsellorMatrixId + "_device",
            new MatrixCallDevice(counsellorMatrixId, now - 900, now + 60000, true, "$start"));
    transaction.executeWithoutResult(status -> bindings.save(running));
    when(matrix.getCallRoomMembers(SOURCE))
        .thenReturn(Optional.of(List.of(ASKER, counsellorMatrixId)));
    when(matrix.getRoomMembers(SOURCE)).thenReturn(Optional.of(List.of(ASKER, counsellorMatrixId)));
    when(matrix.loginAsUserAccessToken(counsellorMatrixId)).thenReturn("counsellor-token");
    when(matrix.removeUserFromRoom(MEDIA, GUEST, "counsellor-token")).thenReturn(true);
    when(matrix.clearUserPowerLevel(MEDIA, GUEST, "counsellor-token")).thenReturn(true);
    when(matrix.getRoomMembership(MEDIA, GUEST, "counsellor-token"))
        .thenReturn(Optional.of("leave"));
  }

  @AfterEach
  void tearDown() {
    transaction.executeWithoutResult(
        status -> {
          guests.deleteAll();
          bindings.deleteAll();
          sessions.deleteById(sessionId);
          users.deleteById("guest-asker");
          users.deleteById("call-guest");
          consultants.deleteById(counsellorId);
        });
  }

  @Test
  void guestEntersTheCallRoomOnlyAndLosesItWhenTheCallEnds() throws Exception {
    var policy = policyService();
    assertThat(policy.resolve(SOURCE, GUEST, MEDIA))
        .as("before admission the guest has no call access")
        .isEqualTo(CallMediaPolicy.denied());

    var counsellor = actor(counsellorId, "consultant");
    mvc(counsellor)
        .perform(
            post("/matrix/calls/guests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceRoomId\":\""
                        + SOURCE
                        + "\",\"callId\":\"guest-call\",\"guestUserId\":\"call-guest\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.callRoomId").value(MEDIA))
        .andExpect(jsonPath("$.guestMatrixUserId").value(GUEST));

    // Only the call room was opened to the guest; the source conversation was never touched.
    verify(matrix).inviteUserToRoom(MEDIA, GUEST, "counsellor-token");
    verify(matrix, never()).inviteUserToRoom(eq(SOURCE), anyString(), anyString());
    verify(matrix, never()).joinRoom(eq(SOURCE), anyString());
    assertThat(policy.resolve(SOURCE, GUEST, MEDIA)).isEqualTo(new CallMediaPolicy(true, true));

    var guest = actor("call-guest", "user");
    mvc(guest)
        .perform(
            get("/matrix/calls/state").param("sourceRoomId", SOURCE).param("callId", "guest-call"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("running"))
        .andExpect(jsonPath("$.callRoomId").value(MEDIA));

    // The counsellor's device leaves: the call ends and the guest's seat is taken back.
    long now = System.currentTimeMillis();
    lifecycle.handleRoom(
        MEDIA,
        Map.of(
            "timeline",
            Map.of(
                "events",
                List.of(
                    Map.of(
                        "type",
                        "org.matrix.msc3401.call.member",
                        "sender",
                        counsellorMatrixId,
                        "state_key",
                        "_" + counsellorMatrixId + "_device",
                        "event_id",
                        "$leave",
                        "origin_server_ts",
                        now,
                        "content",
                        Map.of())))));

    assertThat(bindings.findByMediaRoomId(MEDIA).orElseThrow().getEndedAt()).isNotNull();
    verify(matrix).removeUserFromRoom(MEDIA, GUEST, "counsellor-token");
    verify(matrix).clearUserPowerLevel(MEDIA, GUEST, "counsellor-token");
    verify(matrix, never()).removeUserFromRoom(eq(SOURCE), anyString(), anyString());
    assertThat(guests.findAll())
        .singleElement()
        .satisfies(row -> assertThat(row.getRevokedAt()).isNotNull());
    assertThat(policy.resolve(SOURCE, GUEST, MEDIA)).isEqualTo(CallMediaPolicy.denied());
    mvc(guest)
        .perform(
            get("/matrix/calls/state").param("sourceRoomId", SOURCE).param("callId", "guest-call"))
        .andExpect(status().isNotFound());
    mvc(counsellor)
        .perform(
            post("/matrix/calls/guests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceRoomId\":\""
                        + SOURCE
                        + "\",\"callId\":\"guest-call\",\"guestUserId\":\"call-guest\"}"))
        .andExpect(status().isNotFound());
  }

  /** Security finding 2: an ended call whose guest removal failed is swept by the expiry scan. */
  @Test
  void endedCallWithAnUnremovedGuestIsSweptByTheReconcileScan() throws Exception {
    var admission =
        callGuests.admit(actor(counsellorId, "consultant"), SOURCE, "guest-call", "call-guest");
    assertThat(admission.outcome()).isEqualTo(MatrixCallGuestService.Outcome.ADMITTED);
    when(matrix.removeUserFromRoom(MEDIA, GUEST, "counsellor-token")).thenReturn(false);
    when(matrix.kickUserFromRoom(MEDIA, GUEST, "counsellor-token")).thenReturn(false);
    when(matrix.getRoomMembership(MEDIA, GUEST, "counsellor-token"))
        .thenReturn(Optional.of("invite"));

    lifecycle.handleRoom(MEDIA, Map.of("timeline", Map.of("events", List.of(leave()))));

    assertThat(bindings.findByMediaRoomId(MEDIA).orElseThrow().getEndedAt()).isNotNull();
    assertThat(guests.findAll())
        .singleElement()
        .satisfies(row -> assertThat(row.getRevokedAt()).isNull());

    // The homeserver recovers; no further event arrives for the call room, only the scan runs.
    when(matrix.kickUserFromRoom(MEDIA, GUEST, "counsellor-token")).thenReturn(true);
    when(matrix.getRoomMembership(MEDIA, GUEST, "counsellor-token"))
        .thenReturn(Optional.of("invite"), Optional.of("leave"));
    callReconciler.reconcileMediaRooms();

    assertThat(guests.findAll())
        .singleElement()
        .satisfies(row -> assertThat(row.getRevokedAt()).isNotNull());
  }

  /** Security finding 3: rooms that keep failing must not starve a later room of the sweep. */
  @Test
  void failingRoomsMoveToTheBackSoALaterRoomIsStillSwept() {
    long now = System.currentTimeMillis();
    String late = "!zz-late-media:example";
    transaction.executeWithoutResult(
        status -> {
          for (int i = 0; i < 101; i++) endedCallWithGuest(String.format("!a%03d:example", i), now);
          endedCallWithGuest(late, now);
        });
    when(matrix.getRoomMembership(anyString(), eq(GUEST), eq("counsellor-token")))
        .thenAnswer(call -> Optional.of(late.equals(call.getArgument(0)) ? "leave" : "invite"));
    when(matrix.clearUserPowerLevel(anyString(), eq(GUEST), eq("counsellor-token")))
        .thenReturn(true);

    callReconciler.reconcileMediaRooms();
    callReconciler.reconcileMediaRooms();

    var rows = guests.findAll();
    assertThat(rows).hasSize(102);
    var lateBinding = bindings.findByMediaRoomId(late).orElseThrow();
    assertThat(rows)
        .filteredOn(row -> row.getBindingId().equals(lateBinding.getId()))
        .singleElement()
        .satisfies(row -> assertThat(row.getRevokedAt()).isNotNull());
    assertThat(rows)
        .filteredOn(row -> row.getRevokedAt() == null)
        .hasSize(101)
        .allSatisfy(row -> assertThat(row.getRevokeAttempts()).isPositive());
  }

  private void endedCallWithGuest(String media, long now) {
    var binding =
        bindings.save(
            MatrixCallBinding.builder()
                .sourceRoomId(SOURCE)
                .callId("ended-" + media)
                .mediaRoomId(media)
                .callerMatrixId(counsellorMatrixId)
                .sessionId(sessionId)
                .tenantId(7L)
                .invitedAt(now - 5000)
                .inviteExpiresAt(now - 4000)
                .startedAt(now - 4000)
                .endedAt(now - 1000)
                .mediaObservedAt(now - 4000)
                .video(true)
                .build());
    guests.save(
        de.caritas.cob.userservice.api.model.MatrixCallGuest.builder()
            .bindingId(binding.getId())
            .matrixUserId(GUEST)
            .admittedByMatrixId(counsellorMatrixId)
            .admittedAt(now - 3000)
            .build());
  }

  private Map<String, Object> leave() {
    return Map.of(
        "type",
        "org.matrix.msc3401.call.member",
        "sender",
        counsellorMatrixId,
        "state_key",
        "_" + counsellorMatrixId + "_device",
        "event_id",
        "$leave",
        "origin_server_ts",
        System.currentTimeMillis(),
        "content",
        Map.of());
  }

  private MockMvc mvc(AuthenticatedUser actor) {
    return MockMvcBuilders.standaloneSetup(
            new MatrixCallStateController(callStates, callGuests, actor))
        .build();
  }

  private MatrixRtcCallPolicyService policyService() {
    var tenants = mock(TenantService.class);
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
    return new MatrixRtcCallPolicyService(
        new MatrixRtcPolicyContextResolver(sessions, chats, supervisors, discussions),
        tenants,
        matrix,
        hasher,
        new JdbcTemplate(dataSource));
  }

  private AuthenticatedUser actor(String id, String role) {
    var actor = new AuthenticatedUser();
    actor.setUserId(id);
    actor.setTenantId(7L);
    actor.setRoles(java.util.Set.of(role));
    return actor;
  }

  private User user(String id, String matrixId) {
    return User.builder()
        .userId(id)
        .username(id)
        .email(id + "@synthetic.oriso.test")
        .matrixUserId(matrixId)
        .tenantId(7L)
        .encourage2fa(true)
        .magicLinkLoginEnabled(false)
        .languageCode(LanguageCode.de)
        .build();
  }

  private Consultant consultant() {
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
