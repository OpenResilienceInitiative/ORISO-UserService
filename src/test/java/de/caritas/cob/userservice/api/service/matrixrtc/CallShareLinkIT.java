package de.caritas.cob.userservice.api.service.matrixrtc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.controller.CallShareLinkController;
import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.MatrixCallDevice;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatJoinRequestRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallGuestRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.EffectiveAgencySettingsLookup;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CallShareLinkEndedException;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CallShareLinkService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CallShareLinkSweep;
import de.caritas.cob.userservice.api.service.agencyinvitelink.ConfiguredTemporaryAccountPolicy;
import de.caritas.cob.userservice.api.service.agencyinvitelink.DirectCallKnockService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.EffectiveDirectInvitePolicy;
import de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkAdmission;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallConversationResolver;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallLifecycleService;
import de.caritas.cob.userservice.api.service.matrix.MatrixFeedUpdateSignalService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationDeduplicationWriter;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
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
 * ORISO-UserService#1487 end to end on the real schema: a counsellor in a running call shares it by
 * link, guests knock on the queue of #1479 or join directly through the guest path of #1477, the
 * setting changes on the same link, and the link dies with the call, also when the hang-up is
 * missed. Only the Matrix homeserver and the TenantService are simulated.
 */
@DataJpaTest
@TestPropertySource(properties = {"spring.profiles.active=testing,call-share-link-fixture"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  de.caritas.cob.userservice.api.service.matrix.SessionCallAuthority.class,
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
  de.caritas.cob.userservice.api.service.matrix.MatrixSessionCallBindingService.class,
  de.caritas.cob.userservice.api.service.matrix.MatrixCallBindingService.class,
  de.caritas.cob.userservice.api.service.matrix.MatrixCallBindingWriter.class,
  de.caritas.cob.userservice.api.service.matrix.OrisoRoomRegistry.class,
  de.caritas.cob.userservice.api.service.matrix.MatrixCallInviteNotificationService.class,
  de.caritas.cob.userservice.api.service.matrix.MatrixCallObservationService.class,
  de.caritas.cob.userservice.api.service.matrix.MatrixCallStateService.class,
  CallShareLinkIT.AccountFixture.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CallShareLinkIT {
  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  @org.springframework.context.annotation.Profile("call-share-link-fixture")
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

  private static final String SOURCE = "!share-source:example";
  private static final String MEDIA = "!share-media:example";
  private static final String ASKER = "@share-asker:example";
  private static final String GUEST = "@share-guest:example";
  private static final String SECOND_GUEST = "@share-guest-2:example";
  private static final String CREATE =
      "{\"sourceRoomId\":\"" + SOURCE + "\",\"callId\":\"share-call\"}";

  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;
  @Autowired private SessionRepository sessions;
  @Autowired private ChatRepository chats;
  @Autowired private SessionSupervisorRepository supervisors;
  @Autowired private TeamDiscussionRepository discussions;
  @Autowired private MatrixCallBindingRepository bindings;
  @Autowired private MatrixCallGuestRepository guests;
  @Autowired private AgencyInviteLinkRepository links;
  @Autowired private GroupChatJoinRequestRepository knockRows;
  @Autowired private MatrixCallLifecycleService lifecycle;
  @Autowired private CallShareLinkService shareLinks;
  @Autowired private CallShareLinkSweep sweep;
  @Autowired private DirectCallKnockService knocks;
  @Autowired private MatrixCallGuestService callGuests;

  @Autowired
  private de.caritas.cob.userservice.api.service.matrix.MatrixCallStateService callStates;

  @Autowired
  private de.caritas.cob.userservice.api.service.matrix.MatrixCallInviteNotificationService invites;

  @Autowired
  private de.caritas.cob.userservice.api.service.matrix.MatrixCallObservationService observations;

  @Autowired
  private de.caritas.cob.userservice.api.service.matrix.MatrixSessionCallBindingService
      sessionBindings;

  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private javax.sql.DataSource dataSource;
  @MockitoBean private MatrixSynapseService matrix;

  // Group waiting rooms (#2237) are not part of the call-share-link flow.
  @MockitoBean
  private de.caritas.cob.userservice.api.service.chat.GroupWaitingRoomService waitingRooms;

  @MockitoBean private MatrixFeedUpdateSignalService feedUpdateSignals;
  @MockitoBean private IdentityTombstoneService tombstones;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private EffectiveAgencySettingsLookup effectiveAgencySettings;

  private final ObjectMapper json = new ObjectMapper();
  private TransactionTemplate transaction;
  private Consultant counsellor;
  private Consultant colleague;
  private Long sessionId;

  @BeforeEach
  void setUp() {
    transaction = new TransactionTemplate(transactionManager);
    allowDirectInvites(true, true, true);
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
              var owner = users.save(user("share-asker", ASKER, 7L));
              users.save(user("share-guest", GUEST, 7L));
              users.save(user("share-guest-2", SECOND_GUEST, 7L));
              users.save(user("share-foreign", "@share-foreign:example", 8L));
              counsellor = consultants.save(consultant());
              colleague = consultants.save(consultant());
              var conversation =
                  new Session(owner, 1, "12345", 1L, Session.SessionStatus.IN_PROGRESS, false);
              conversation.setTenantId(7L);
              conversation.setAgencyId(99L);
              conversation.setMatrixRoomId(SOURCE);
              conversation.setLanguageCode(LanguageCode.de);
              conversation.setIsConsultantDirectlySet(false);
              conversation.setConsultant(counsellor);
              return sessions.save(conversation).getId();
            });
    for (String identity :
        List.of(
            "share-asker",
            "share-guest",
            "share-guest-2",
            "share-foreign",
            counsellor.getId(),
            colleague.getId())) {
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
            .callId("share-call")
            .mediaRoomId(MEDIA)
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
            device(),
            new MatrixCallDevice(
                counsellor.getMatrixUserId(), now - 900, now + 600000, true, "$start"));
    transaction.executeWithoutResult(status -> bindings.save(running));
    var members = List.of(ASKER, counsellor.getMatrixUserId());
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(members));
    when(matrix.getRoomMembers(SOURCE)).thenReturn(Optional.of(members));
    when(matrix.loginAsUserAccessToken(counsellor.getMatrixUserId())).thenReturn("c-token");
    when(matrix.getRoomMembership(eq(MEDIA), anyString(), eq("c-token")))
        .thenReturn(Optional.of("leave"));
    when(matrix.removeUserFromRoom(eq(MEDIA), anyString(), eq("c-token"))).thenReturn(true);
    when(matrix.clearUserPowerLevel(eq(MEDIA), anyString(), eq("c-token"))).thenReturn(true);
  }

  @AfterEach
  void tearDown() {
    var cleanup = new JdbcTemplate(dataSource);
    cleanup.update("DELETE FROM matrix_call_observation_end");
    cleanup.update("DELETE FROM matrix_call_observation");
    transaction.executeWithoutResult(
        status -> {
          knockRows.deleteAll();
          links.deleteAll();
          guests.deleteAll();
          bindings.deleteAll();
          sessions.deleteById(sessionId);
          for (String id : List.of("share-asker", "share-guest", "share-guest-2", "share-foreign"))
            users.deleteById(id);
          consultants.deleteById(counsellor.getId());
          consultants.deleteById(colleague.getId());
        });
  }

  @Test
  void audioSwitchedOff_Should_RefuseTheShareLink_ButNotTheOtherMedia() throws Exception {
    allowDirectInvites(true, false, true);
    mvc(counsellor.getId(), "consultant", null)
        .perform(
            post("/matrix/calls/share-links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE))
        .andExpect(status().isForbidden());
    assertThat(links.count()).isZero();
  }

  @Test
  void withdrawnPermission_Should_KeepTheLinkButRefuseGuests() throws Exception {
    var created =
        mvc(counsellor.getId(), "consultant", null)
            .perform(
                post("/matrix/calls/share-links")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CREATE))
            .andExpect(status().isCreated())
            .andReturn();
    String token = json.readTree(created.getResponse().getContentAsString()).get("token").asText();
    var guest = users.findById("share-guest").orElseThrow();

    allowDirectInvites(true, false, true);
    assertThatThrownBy(() -> shareLinks.join(token, guest))
        .isInstanceOf(
            de.caritas.cob.userservice.api.service.agencyinvitelink.DirectLinkInactiveException
                .class);
    assertThat(links.findByToken(token)).get().extracting("status").isEqualTo("ACTIVE");

    allowDirectInvites(true, true, true);
    assertThat(shareLinks.join(token, guest).knockId()).isNotNull();
  }

  /** What the AgencyService serves as the effective "Direkteinladung erlauben" per chat type. */
  private void allowDirectInvites(boolean chat, boolean audio, boolean video) {
    when(tenantService.getRestrictedTenantDataFresh(7L))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(7L)
                .settings(
                    new Settings()
                        .featureDirectInviteChatEnabled(chat)
                        .featureDirectInviteAudioEnabled(audio)
                        .featureDirectInviteVideoEnabled(video)));
    when(effectiveAgencySettings.findEffectiveSettings(anyLong(), anyLong()))
        .thenReturn(
            Optional.of(
                new de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings()
                    .featureDirectInviteChatEnabled(chat)
                    .featureDirectInviteAudioEnabled(audio)
                    .featureDirectInviteVideoEnabled(video)));
  }

  @Test
  void guestsKnockOrJoinThroughTheSameLink_AndTheLinkDiesWithTheCall() throws Exception {
    var policy = policyService();
    var created =
        mvc(counsellor.getId(), "consultant", null)
            .perform(
                post("/matrix/calls/share-links")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CREATE))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.knock").value(true))
            .andExpect(jsonPath("$.temporaryAccount").value(true))
            .andExpect(jsonPath("$.temporaryAccountAllowed").value(true))
            .andExpect(jsonPath("$.medium").value("AUDIO"))
            .andExpect(jsonPath("$.active").value(true))
            .andReturn();
    var body = json.readTree(created.getResponse().getContentAsString());
    long linkId = body.get("id").asLong();
    String token = body.get("token").asText();
    assertThat(body.get("url").asText()).endsWith("/invite/" + token);

    // A second "share" in the same call returns the same link.
    mvc(counsellor.getId(), "consultant", null)
        .perform(
            post("/matrix/calls/share-links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(linkId));

    // Anklopfen on: the first guest waits; the call stays closed to them.
    var guest = users.findById("share-guest").orElseThrow();
    var waiting = shareLinks.join(token, guest);
    assertThat(waiting.callAdmitted()).isFalse();
    assertThat(waiting.callRoomId()).isNull();
    assertThat(policy.resolve(SOURCE, GUEST, MEDIA)).isEqualTo(CallMediaPolicy.denied());
    assertThat(shareLinks.join(token, guest).knockId())
        .as("knocking again keeps one knock")
        .isEqualTo(waiting.knockId());

    // The counsellor sees the knock in the existing queue and lets the guest in.
    var counsellorEntity = consultants.findById(counsellor.getId()).orElseThrow();
    assertThat(knocks.pendingFor(counsellorEntity, null, null)).hasSize(1);
    assertThat(knocks.pendingFor(counsellorEntity, null, SOURCE))
        .as("the per-call filter finds the call-link knock")
        .singleElement()
        .satisfies(
            knock -> {
              assertThat(knock.getVia()).isEqualTo(GroupChatJoinRequest.Via.CALL_LINK);
              assertThat(knock.getInviteLinkId()).isEqualTo(linkId);
            });
    transaction.executeWithoutResult(s -> knocks.admit(waiting.knockId(), counsellorEntity));
    var seated = shareLinks.join(token, guest);
    assertThat(seated.callAdmitted()).isTrue();
    assertThat(seated.callRoomId()).isEqualTo(MEDIA);
    verify(matrix).inviteUserToRoom(MEDIA, GUEST, "c-token");
    verify(matrix, never()).inviteUserToRoom(eq(SOURCE), anyString(), anyString());
    assertThat(policy.resolve(SOURCE, GUEST, MEDIA)).isEqualTo(new CallMediaPolicy(true, true));

    // Anklopfen off on the same link: the next guest joins at once.
    mvc(counsellor.getId(), "consultant", null)
        .perform(
            patch("/matrix/calls/share-links/" + linkId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"knock\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").value(token))
        .andExpect(jsonPath("$.knock").value(false));
    var second = shareLinks.join(token, users.findById("share-guest-2").orElseThrow());
    assertThat(second.callAdmitted()).isTrue();
    assertThat(second.admission()).isEqualTo(InviteLinkAdmission.DIRECT);
    verify(matrix).inviteUserToRoom(MEDIA, SECOND_GUEST, "c-token");

    // Hang-up: the link no longer works, for guests and for the counsellor.
    lifecycle.handleRoom(MEDIA, Map.of("timeline", Map.of("events", List.of(leave()))));
    assertThat(bindings.findByMediaRoomId(MEDIA).orElseThrow().getEndedAt()).isNotNull();
    assertThatThrownBy(() -> shareLinks.join(token, guest))
        .isInstanceOf(CallShareLinkEndedException.class);
    mvc(counsellor.getId(), "consultant", null)
        .perform(
            patch("/matrix/calls/share-links/" + linkId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"knock\":true}"))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.reason").value("CALL_SHARE_LINK_ENDED"));
    sweep.sweep();
    assertThat(links.findById(linkId).orElseThrow().getStatus()).isEqualTo("EXPIRED");
  }

  @Test
  void onlyACounsellorInTheCallManagesTheLink_AndOnlyGuestsOfTheTraegerUseIt() throws Exception {
    var link = shareLinks.create(7L, counsellor.getId(), SOURCE, "share-call", null, null).link();

    // A colleague of the same Träger who is not in the conversation: as if the link did not exist.
    mvc(colleague.getId(), "consultant", null)
        .perform(
            patch("/matrix/calls/share-links/" + link.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"knock\":false}"))
        .andExpect(status().isNotFound());
    mvc(colleague.getId(), "consultant", null)
        .perform(
            post("/matrix/calls/share-links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE))
        .andExpect(status().isNotFound());
    // The counsellor of another Träger.
    mvc(counsellor.getId(), "consultant", 8L)
        .perform(
            patch("/matrix/calls/share-links/" + link.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"knock\":false}"))
        .andExpect(status().isNotFound());
    // An asker cannot change settings, not even their own call's link.
    mvc("share-guest", "user", null)
        .perform(
            patch("/matrix/calls/share-links/" + link.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"knock\":false}"))
        .andExpect(status().isForbidden());
    assertThat(links.findById(link.getId()).orElseThrow().isKnock()).isTrue();

    var foreign = users.findById("share-foreign").orElseThrow();
    assertThatThrownBy(() -> shareLinks.join(link.getToken(), foreign))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
    assertThat(knockRows.findAll()).isEmpty();

    mvc(counsellor.getId(), "consultant", null)
        .perform(
            get("/matrix/calls/share-links")
                .param("sourceRoomId", SOURCE)
                .param("callId", "share-call"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(link.getId()));
  }

  /**
   * FE#2209: an encrypted 1:1 call has no readable invite. The session counsellor's verified
   * registration binds it, and the same share link, knock and seat then work against a real
   * database. A binding nobody was ever seen in ends by itself and seats nobody afterwards.
   */
  @Test
  void aSessionCounsellorsVerifiedRegistrationBacksShareLinkKnockAndSeat() throws Exception {
    String encryptedMedia = "!share-encrypted-media:example";
    String caller = counsellor.getMatrixUserId();
    when(matrix.getCallRoomBinding(encryptedMedia, caller))
        .thenReturn(Optional.of(Map.of("call_id", "encrypted-call", "source_room_id", SOURCE)));
    when(matrix.getCallRoomOrigin(encryptedMedia, caller))
        .thenReturn(Optional.of(new MatrixSynapseService.CallRoomOrigin(caller, caller)));
    when(matrix.getRoomMembership(eq(encryptedMedia), anyString(), eq("c-token")))
        .thenReturn(Optional.of("leave"));
    when(matrix.removeUserFromRoom(eq(encryptedMedia), anyString(), eq("c-token")))
        .thenReturn(true);
    when(matrix.clearUserPowerLevel(eq(encryptedMedia), anyString(), eq("c-token")))
        .thenReturn(true);

    // Nobody but the session's counsellor can bind it.
    assertThat(
            sessionBindings.bind(
                actor(colleague.getId(), "consultant"),
                SOURCE,
                "encrypted-call",
                encryptedMedia,
                true))
        .isFalse();
    assertThat(
            sessionBindings.bind(
                actor("share-asker", "user"), SOURCE, "encrypted-call", encryptedMedia, true))
        .isFalse();
    // Not even the counsellor can bind another ORISO room (here: the session room) as call room.
    assertThat(
            sessionBindings.bind(
                actor(counsellor.getId(), "consultant"), SOURCE, "encrypted-call", SOURCE, true))
        .isFalse();
    assertThat(bindings.findBySourceRoomIdAndCallId(SOURCE, "encrypted-call")).isEmpty();

    assertThat(
            sessionBindings.bind(
                actor(counsellor.getId(), "consultant"),
                SOURCE,
                "encrypted-call",
                encryptedMedia,
                true))
        .isTrue();
    var bound = bindings.findBySourceRoomIdAndCallId(SOURCE, "encrypted-call").orElseThrow();
    assertThat(bound.getSessionId()).isEqualTo(sessionId);
    assertThat(bound.getMediaRoomId()).isEqualTo(encryptedMedia);
    // The counsellor's MatrixRTC membership in the call room, as the lifecycle records it.
    inCall(encryptedMedia, caller, System.currentTimeMillis() + 600000);

    var link =
        shareLinks.create(7L, counsellor.getId(), SOURCE, "encrypted-call", null, null).link();
    var guest = users.findById("share-guest").orElseThrow();
    var waiting = shareLinks.join(link.getToken(), guest);
    assertThat(waiting.callAdmitted()).isFalse();
    var counsellorEntity = consultants.findById(counsellor.getId()).orElseThrow();
    transaction.executeWithoutResult(s -> knocks.admit(waiting.knockId(), counsellorEntity));
    var seated = shareLinks.join(link.getToken(), guest);
    assertThat(seated.callAdmitted()).isTrue();
    assertThat(seated.callRoomId()).isEqualTo(encryptedMedia);
    verify(matrix).inviteUserToRoom(encryptedMedia, GUEST, "c-token");

    // Never observed and nobody seen: once the invite ran out by the margin, the call ends.
    long later =
        bound.getInviteExpiresAt()
            + de.caritas.cob.userservice.api.service.matrix.MatrixCallLifecycleService
                .NEVER_OBSERVED_MARGIN_MILLIS
            + 1;
    assertThat(lifecycle.finishNeverObserved(encryptedMedia, later)).isTrue();
    assertThat(bindings.findByMediaRoomId(encryptedMedia).orElseThrow().getEndedAt()).isNotNull();
    // The guest admitted before the end loses the seat: row revoked, removed, power level cleared.
    assertThat(
            guests
                .findByBindingIdAndMatrixUserId(bound.getId(), GUEST)
                .orElseThrow()
                .getRevokedAt())
        .isNotNull();
    verify(matrix).removeUserFromRoom(encryptedMedia, GUEST, "c-token");
    verify(matrix).clearUserPowerLevel(encryptedMedia, GUEST, "c-token");
    assertThat(
            callGuests
                .admitAs(7L, counsellor.getId(), SOURCE, "encrypted-call", "share-guest-2")
                .outcome())
        .isEqualTo(MatrixCallGuestService.Outcome.NOT_FOUND);
    assertThatThrownBy(() -> shareLinks.join(link.getToken(), guest))
        .isInstanceOf(CallShareLinkEndedException.class);
    // An ended binding is not renewed by another registration.
    assertThat(
            sessionBindings.bind(
                actor(counsellor.getId(), "consultant"),
                SOURCE,
                "encrypted-call",
                encryptedMedia,
                true))
        .isFalse();
  }

  private static de.caritas.cob.userservice.api.helper.AuthenticatedUser actor(
      String id, String role) {
    var actor = new de.caritas.cob.userservice.api.helper.AuthenticatedUser();
    actor.setUserId(id);
    actor.setTenantId(7L);
    actor.setRoles(java.util.Set.of(role));
    return actor;
  }

  /** Security review of #1487: an admission can be taken back, and "Anklopfen" on again holds. */
  @Test
  void aRemovedGuestLosesTheCallAtOnce_AndKnockOnAgainOnlyAffectsRejoins() throws Exception {
    var policy = policyService();
    var link = shareLinks.create(7L, counsellor.getId(), SOURCE, "share-call", false, null).link();
    var guest = users.findById("share-guest").orElseThrow();
    var second = users.findById("share-guest-2").orElseThrow();
    assertThat(shareLinks.join(link.getToken(), guest).callAdmitted()).isTrue();
    assertThat(shareLinks.join(link.getToken(), second).callAdmitted()).isTrue();
    assertThat(knockRows.findAll()).allSatisfy(row -> assertThat(row.getSessionId()).isNull());

    // "Anklopfen" back on: whoever is in the call stays; a re-join knocks.
    mvc(counsellor.getId(), "consultant", null)
        .perform(
            patch("/matrix/calls/share-links/" + link.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"knock\":true}"))
        .andExpect(status().isOk());
    assertThat(policy.resolve(SOURCE, SECOND_GUEST, MEDIA))
        .isEqualTo(new CallMediaPolicy(true, true));
    var rejoin = shareLinks.join(link.getToken(), second);
    assertThat(rejoin.callAdmitted()).isFalse();
    assertThat(knockRows.findById(rejoin.knockId()).orElseThrow().getSessionId()).isNull();

    // The counsellor removes the first guest mid-call.
    mvc(counsellor.getId(), "consultant", null)
        .perform(
            post("/matrix/calls/share-links/" + link.getId() + "/guests/remove")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"matrixUserId\":\"" + GUEST + "\"}"))
        .andExpect(status().isNoContent());
    assertThat(policy.resolve(SOURCE, GUEST, MEDIA)).isEqualTo(CallMediaPolicy.denied());
    verify(matrix).removeUserFromRoom(MEDIA, GUEST, "c-token");
    assertThatThrownBy(() -> shareLinks.join(link.getToken(), guest))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
    // Not even an admission brought about by switching "Anklopfen" off lets them back in.
    shareLinks.update(7L, counsellor.getId(), link.getId(), false, null);
    assertThatThrownBy(() -> shareLinks.join(link.getToken(), guest))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
    assertThat(
            callGuestService().admitAs(7L, counsellor.getId(), SOURCE, "share-call", "share-guest"))
        .extracting(MatrixCallGuestService.Admission::outcome)
        .isEqualTo(MatrixCallGuestService.Outcome.NOT_FOUND);
    // The colleague outside the call cannot remove anybody.
    mvc(colleague.getId(), "consultant", null)
        .perform(
            post("/matrix/calls/share-links/" + link.getId() + "/guests/remove")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"matrixUserId\":\"" + SECOND_GUEST + "\"}"))
        .andExpect(status().isNotFound());
    assertThat(policy.resolve(SOURCE, SECOND_GUEST, MEDIA))
        .isEqualTo(new CallMediaPolicy(true, true));
  }

  /** Frank, 2026-10-28: a knock on the share link reaches every counsellor in the call. */
  @Test
  void aCoModeratorInTheCallSeesAndDecidesTheKnock_ACounsellorOutsideItDoesNot() {
    var link = shareLinks.create(7L, counsellor.getId(), SOURCE, "share-call", null, null).link();
    var guest = users.findById("share-guest").orElseThrow();
    var knockId = shareLinks.join(link.getToken(), guest).knockId();
    var colleagueEntity = consultants.findById(colleague.getId()).orElseThrow();

    // Not in the call: nothing listed, deciding is a 404.
    assertThat(knocks.pendingFor(colleagueEntity, null, SOURCE)).isEmpty();
    assertThatThrownBy(
            () -> transaction.executeWithoutResult(s -> knocks.admit(knockId, colleagueEntity)))
        .isInstanceOf(NotFoundException.class);

    // In the conversation but not in the call: still nothing to decide (FE#2209 review).
    var members = List.of(ASKER, counsellor.getMatrixUserId(), colleague.getMatrixUserId());
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(members));
    when(matrix.getRoomMembers(SOURCE)).thenReturn(Optional.of(members));
    assertThatThrownBy(
            () -> transaction.executeWithoutResult(s -> knocks.admit(knockId, colleagueEntity)))
        .isInstanceOf(NotFoundException.class);

    // Joins the call: sees the knock in the per-call list and lets the guest in.
    inCall(MEDIA, colleague.getMatrixUserId(), System.currentTimeMillis() + 600000);
    when(matrix.loginAsUserAccessToken(colleague.getMatrixUserId())).thenReturn("c-token");
    assertThat(knocks.pendingFor(colleagueEntity, null, SOURCE))
        .extracting(GroupChatJoinRequest::getId)
        .containsExactly(knockId);
    assertThat(knocks.pendingFor(colleagueEntity, null, null))
        .as("the unscoped list stays with the link's creator")
        .isEmpty();
    transaction.executeWithoutResult(s -> knocks.admit(knockId, colleagueEntity));

    assertThat(knockRows.findById(knockId).orElseThrow().getDecidedBy())
        .isEqualTo(colleague.getId());
    assertThat(shareLinks.join(link.getToken(), guest).callAdmitted()).isTrue();
  }

  // --- FE#2209 decision of 13 Nov: any member's session call rings; only the assigned
  // counsellor's binding opens it to guests ---

  private Map<String, Object> plaintextInvite(String sender, String callId, String media) {
    return Map.of(
        "type",
        "org.oriso.call.invite",
        "sender",
        sender,
        "event_id",
        "$" + callId + sender,
        "origin_server_ts",
        System.currentTimeMillis(),
        "content",
        Map.of("call_id", callId, "call_room_id", media, "lifetime", 60000, "is_video", true));
  }

  private int invitationsOf(String recipientId, String callId) {
    return new JdbcTemplate(dataSource)
        .queryForObject(
            "SELECT COUNT(*) FROM event_notification WHERE recipient_user_id = ?"
                + " AND event_type = 'call.invited' AND params LIKE ?",
            Integer.class,
            recipientId,
            "%" + callId + "%");
  }

  @Test
  void anAskersPlaintextInviteRingsButYieldsNoShareLinkKnockOrSeat() throws Exception {
    String askerMedia = "!asker-media:example";
    when(matrix.getCallRoomBinding(askerMedia, ASKER))
        .thenReturn(Optional.of(Map.of("call_id", "asker-call", "source_room_id", SOURCE)));
    when(matrix.ensureAdminInRoom(anyString(), anyString())).thenReturn(true);

    assertThat(invites.handle(SOURCE, plaintextInvite(ASKER, "asker-call", askerMedia))).isTrue();

    // Rings as before: the counsellor has the invitation, and the call has its lifecycle record.
    assertThat(invitationsOf(counsellor.getId(), "asker-call")).isPositive();
    var askers = bindings.findBySourceRoomIdAndCallId(SOURCE, "asker-call").orElseThrow();
    assertThat(askers.getCallerMatrixId()).isEqualTo(ASKER);
    // The counsellor is even in that call; still no link, no knock, no seat.
    inCall(askerMedia, counsellor.getMatrixUserId(), System.currentTimeMillis() + 600000);
    assertThatThrownBy(
            () -> shareLinks.create(7L, counsellor.getId(), SOURCE, "asker-call", null, null))
        .isInstanceOf(NotFoundException.class);
    assertThat(
            callGuests
                .admitAs(7L, counsellor.getId(), SOURCE, "asker-call", "share-guest")
                .outcome())
        .isEqualTo(MatrixCallGuestService.Outcome.NOT_FOUND);
    verify(matrix, never()).inviteUserToRoom(eq(askerMedia), anyString(), anyString());
  }

  @Test
  void afterAnAskersInviteTheCounsellorsBindingTakesOverAndGuestsLandInTheCounsellorsRoom()
      throws Exception {
    String askerMedia = "!asker-media-2:example";
    String counsellorMedia = "!counsellor-media-2:example";
    String caller = counsellor.getMatrixUserId();
    when(matrix.getCallRoomBinding(askerMedia, ASKER))
        .thenReturn(Optional.of(Map.of("call_id", "taken-call", "source_room_id", SOURCE)));
    when(matrix.getCallRoomBinding(counsellorMedia, caller))
        .thenReturn(Optional.of(Map.of("call_id", "taken-call", "source_room_id", SOURCE)));
    when(matrix.getCallRoomOrigin(counsellorMedia, caller))
        .thenReturn(Optional.of(new MatrixSynapseService.CallRoomOrigin(caller, caller)));
    when(matrix.ensureAdminInRoom(anyString(), anyString())).thenReturn(true);
    when(matrix.getRoomMembership(eq(counsellorMedia), anyString(), eq("c-token")))
        .thenReturn(Optional.of("leave"));
    assertThat(invites.handle(SOURCE, plaintextInvite(ASKER, "taken-call", askerMedia))).isTrue();
    long id = bindings.findBySourceRoomIdAndCallId(SOURCE, "taken-call").orElseThrow().getId();

    assertThat(
            sessionBindings.bind(
                actor(counsellor.getId(), "consultant"),
                SOURCE,
                "taken-call",
                counsellorMedia,
                true))
        .isTrue();

    var taken = bindings.findBySourceRoomIdAndCallId(SOURCE, "taken-call").orElseThrow();
    assertThat(taken.getId()).as("the same row, so the unique key holds").isEqualTo(id);
    assertThat(taken.getCallerMatrixId()).isEqualTo(caller);
    assertThat(taken.getMediaRoomId()).isEqualTo(counsellorMedia);
    assertThat(bindings.findByMediaRoomId(askerMedia)).isEmpty();
    inCall(counsellorMedia, caller, System.currentTimeMillis() + 600000);
    var link = shareLinks.create(7L, counsellor.getId(), SOURCE, "taken-call", null, null).link();
    var guest = users.findById("share-guest").orElseThrow();
    var knockId = shareLinks.join(link.getToken(), guest).knockId();
    var counsellorEntity = consultants.findById(counsellor.getId()).orElseThrow();
    transaction.executeWithoutResult(s -> knocks.admit(knockId, counsellorEntity));
    var seated = shareLinks.join(link.getToken(), guest);
    assertThat(seated.callAdmitted()).isTrue();
    assertThat(seated.callRoomId()).isEqualTo(counsellorMedia);
    verify(matrix).inviteUserToRoom(counsellorMedia, GUEST, "c-token");
    verify(matrix, never()).inviteUserToRoom(eq(askerMedia), anyString(), anyString());
  }

  @Test
  void aTakeOverLeavesNoStaleLinkKnockOrSeatOfTheOldCall() throws Exception {
    // Security review of FE#2209: the session is reassigned while its call runs with a share link,
    // a seated guest and a waiting knock. The old binding loses its authority; the newly assigned
    // counsellor's bind takes the row over and nothing of the old call carries into the new room.
    var link = shareLinks.create(7L, counsellor.getId(), SOURCE, "share-call", null, null).link();
    var guest = users.findById("share-guest").orElseThrow();
    var knockId = shareLinks.join(link.getToken(), guest).knockId();
    var counsellorEntity = consultants.findById(counsellor.getId()).orElseThrow();
    transaction.executeWithoutResult(s -> knocks.admit(knockId, counsellorEntity));
    assertThat(shareLinks.join(link.getToken(), guest).callAdmitted()).isTrue();
    var waiting =
        shareLinks.join(link.getToken(), users.findById("share-guest-2").orElseThrow()).knockId();
    long id = bindings.findBySourceRoomIdAndCallId(SOURCE, "share-call").orElseThrow().getId();

    String colleagueId = colleague.getMatrixUserId();
    String colleagueMedia = "!reassigned-media:example";
    transaction.executeWithoutResult(
        s -> {
          var session = sessions.findById(sessionId).orElseThrow();
          session.setConsultant(consultants.findById(colleague.getId()).orElseThrow());
          sessions.save(session);
        });
    var members = List.of(ASKER, counsellor.getMatrixUserId(), colleagueId);
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(members));
    when(matrix.getRoomMembers(SOURCE)).thenReturn(Optional.of(members));
    when(matrix.getCallRoomBinding(colleagueMedia, colleagueId))
        .thenReturn(Optional.of(Map.of("call_id", "share-call", "source_room_id", SOURCE)));
    when(matrix.getCallRoomOrigin(colleagueMedia, colleagueId))
        .thenReturn(Optional.of(new MatrixSynapseService.CallRoomOrigin(colleagueId, colleagueId)));
    when(matrix.ensureAdminInRoom(anyString(), anyString())).thenReturn(true);

    assertThat(
            sessionBindings.bind(
                actor(colleague.getId(), "consultant"), SOURCE, "share-call", colleagueMedia, true))
        .isTrue();

    var taken = bindings.findBySourceRoomIdAndCallId(SOURCE, "share-call").orElseThrow();
    assertThat(taken.getId()).isEqualTo(id);
    assertThat(taken.getMediaRoomId()).isEqualTo(colleagueMedia);
    // The old link is closed and no longer tied to the call; its token lets nobody in.
    var old = links.findById(link.getId()).orElseThrow();
    assertThat(old.getStatus()).isEqualTo("EXPIRED");
    assertThat(old.getCallBindingId()).isNull();
    assertThatThrownBy(() -> shareLinks.join(link.getToken(), guest))
        .isInstanceOf(CallShareLinkEndedException.class);
    assertThat(knockRows.findById(waiting).orElseThrow().getStatus())
        .isEqualTo(GroupChatJoinRequest.Status.CANCELLED);
    // The seated guest was taken out of the OLD call room, and holds no seat in the new call.
    verify(matrix).removeUserFromRoom(MEDIA, GUEST, "c-token");
    verify(matrix, never()).inviteUserToRoom(eq(colleagueMedia), anyString(), anyString());
    assertThat(guests.findByBindingIdAndRevokedAtIsNull(id)).isEmpty();
    // Round 3: the seat record stays, so "removed, cannot come back" (#1487) still holds.
    assertThat(callGuests.wasRemoved(taken, GUEST)).isTrue();
    assertThat(guests.findByBindingId(id))
        .extracting(seat -> seat.getMatrixUserId())
        .contains(GUEST);
  }

  // --- FE#2209 round 3 (R1): nothing an asker does blocks the assigned counsellor's bind ---

  private String stubCounsellorsCallRoom(String callId, String media) {
    String caller = counsellor.getMatrixUserId();
    when(matrix.getCallRoomBinding(media, caller))
        .thenReturn(Optional.of(Map.of("call_id", callId, "source_room_id", SOURCE)));
    when(matrix.getCallRoomOrigin(media, caller))
        .thenReturn(Optional.of(new MatrixSynapseService.CallRoomOrigin(caller, caller)));
    when(matrix.ensureAdminInRoom(anyString(), anyString())).thenReturn(true);
    return caller;
  }

  @Test
  void anAskersFewSecondInviteThatTheServerClosedDoesNotBlockTheCounsellorsCall() throws Exception {
    // Round 5: the browser sends the encrypted invite before it binds. The asker decrypts the call
    // id, posts a plaintext invite with it, and the server closes that never-observed binding once
    // its invite ran out. The counsellor's call with that id still works, with share links.
    String askerMedia = "!short-asker-media:example";
    String counsellorMedia = "!short-counsellor-media:example";
    when(matrix.getCallRoomBinding(askerMedia, ASKER))
        .thenReturn(Optional.of(Map.of("call_id", "short-call", "source_room_id", SOURCE)));
    String caller = stubCounsellorsCallRoom("short-call", counsellorMedia);
    when(matrix.getRoomMembership(eq(counsellorMedia), anyString(), eq("c-token")))
        .thenReturn(Optional.of("leave"));
    assertThat(invites.handle(SOURCE, plaintextInvite(ASKER, "short-call", askerMedia))).isTrue();
    var askers = bindings.findBySourceRoomIdAndCallId(SOURCE, "short-call").orElseThrow();
    assertThat(
            lifecycle.finishNeverObserved(
                askerMedia,
                askers.getInviteExpiresAt()
                    + MatrixCallLifecycleService.NEVER_OBSERVED_MARGIN_MILLIS
                    + 1))
        .isTrue();
    assertThat(bindings.findBySourceRoomIdAndCallId(SOURCE, "short-call").orElseThrow())
        .satisfies(
            closed -> {
              assertThat(closed.getEndedAt()).isNotNull();
              assertThat(closed.getEndCause()).isEqualTo(MatrixCallBinding.END_CAUSE_LIFECYCLE);
            });
    // The counsellor is not shown the asker's closed call as a missed call.
    assertThat(callStates.read(actor(counsellor.getId(), "consultant"), SOURCE, "short-call"))
        .map(state -> state.state())
        .isNotEqualTo(Optional.of("missed"));

    assertThat(
            sessionBindings.bind(
                actor(counsellor.getId(), "consultant"),
                SOURCE,
                "short-call",
                counsellorMedia,
                true))
        .isTrue();
    var taken = bindings.findBySourceRoomIdAndCallId(SOURCE, "short-call").orElseThrow();
    assertThat(taken.getEndedAt()).isNull();
    assertThat(taken.getCallerMatrixId()).isEqualTo(caller);
    assertThat(taken.getMediaRoomId()).isEqualTo(counsellorMedia);
    inCall(counsellorMedia, caller, System.currentTimeMillis() + 600000);
    var link = shareLinks.create(7L, counsellor.getId(), SOURCE, "short-call", null, null).link();
    assertThat(link.getStatus()).isEqualTo("ACTIVE");
    var guest = users.findById("share-guest").orElseThrow();
    assertThat(shareLinks.join(link.getToken(), guest).knockId()).isNotNull();
  }

  @Test
  void aCounsellorsExplicitlyEndedCallIsNeverRevived() throws Exception {
    // Round 5, R4 stays strict: the counsellor ended the call through the endpoint; a later bind
    // of the same call id (for example by a newly assigned counsellor) does not bring it back.
    String media = "!ended-media:example";
    stubCounsellorsCallRoom("ended-call", media);
    var actor = actor(counsellor.getId(), "consultant");
    assertThat(sessionBindings.bind(actor, SOURCE, "ended-call", media, true)).isTrue();
    assertThat(observations.end(actor, SOURCE, "ended-call"))
        .isEqualTo(
            de.caritas.cob.userservice.api.service.matrix.MatrixCallObservationService.Outcome.OK);
    var ended = bindings.findBySourceRoomIdAndCallId(SOURCE, "ended-call").orElseThrow();
    assertThat(ended.getEndCause()).isEqualTo(MatrixCallBinding.END_CAUSE_ENDPOINT);
    String newMedia = "!ended-new-media:example";
    stubCounsellorsCallRoom("ended-call", newMedia);
    assertThat(sessionBindings.bind(actor, SOURCE, "ended-call", newMedia, true)).isFalse();
    assertThat(bindings.findBySourceRoomIdAndCallId(SOURCE, "ended-call").orElseThrow())
        .satisfies(
            still -> {
              assertThat(still.getEndedAt()).isEqualTo(ended.getEndedAt());
              assertThat(still.getMediaRoomId()).isEqualTo(media);
            });
  }

  @Test
  void anAlreadyExpiredAskerInviteCreatesNoBinding_AndTheCounsellorBinds() throws Exception {
    String askerMedia = "!expired-asker-media:example";
    String counsellorMedia = "!expired-counsellor-media:example";
    when(matrix.getCallRoomBinding(askerMedia, ASKER))
        .thenReturn(Optional.of(Map.of("call_id", "lt1-call", "source_room_id", SOURCE)));
    stubCounsellorsCallRoom("lt1-call", counsellorMedia);
    var invite = new java.util.HashMap<>(plaintextInvite(ASKER, "lt1-call", askerMedia));
    invite.put("origin_server_ts", System.currentTimeMillis() - 10_000);
    invite.put(
        "content",
        Map.of("call_id", "lt1-call", "call_room_id", askerMedia, "lifetime", 1, "is_video", true));

    invites.handle(SOURCE, invite);

    assertThat(bindings.findBySourceRoomIdAndCallId(SOURCE, "lt1-call")).isEmpty();
    assertThat(
            sessionBindings.bind(
                actor(counsellor.getId(), "consultant"), SOURCE, "lt1-call", counsellorMedia, true))
        .isTrue();
    assertThat(
            bindings.findBySourceRoomIdAndCallId(SOURCE, "lt1-call").orElseThrow().getMediaRoomId())
        .isEqualTo(counsellorMedia);
  }

  @Test
  void aLapsedAskerBindingIsTakenOverByTheCounsellor() throws Exception {
    String askerMedia = "!lapsed-asker-media:example";
    String counsellorMedia = "!lapsed-counsellor-media:example";
    when(matrix.getCallRoomBinding(askerMedia, ASKER))
        .thenReturn(Optional.of(Map.of("call_id", "lapsed-call", "source_room_id", SOURCE)));
    String caller = stubCounsellorsCallRoom("lapsed-call", counsellorMedia);
    invites.handle(SOURCE, plaintextInvite(ASKER, "lapsed-call", askerMedia));
    new JdbcTemplate(dataSource)
        .update(
            "UPDATE matrix_call_binding SET invite_expires_at = ? WHERE call_id = 'lapsed-call'",
            System.currentTimeMillis() - 1);

    assertThat(
            sessionBindings.bind(
                actor(counsellor.getId(), "consultant"),
                SOURCE,
                "lapsed-call",
                counsellorMedia,
                true))
        .isTrue();
    var taken = bindings.findBySourceRoomIdAndCallId(SOURCE, "lapsed-call").orElseThrow();
    assertThat(taken.getCallerMatrixId()).isEqualTo(caller);
    assertThat(taken.getMediaRoomId()).isEqualTo(counsellorMedia);
  }

  @Test
  void anAskersBindingAndEndNeitherBlockTheCounsellorsRegistrationNorBind() throws Exception {
    String askerMedia = "!ended-asker-media:example";
    String counsellorMedia = "!ended-counsellor-media:example";
    when(matrix.getCallRoomBinding(askerMedia, ASKER))
        .thenReturn(Optional.of(Map.of("call_id", "asker-ended", "source_room_id", SOURCE)));
    stubCounsellorsCallRoom("asker-ended", counsellorMedia);
    invites.handle(SOURCE, plaintextInvite(ASKER, "asker-ended", askerMedia));
    assertThat(bindings.findBySourceRoomIdAndCallId(SOURCE, "asker-ended")).isPresent();

    // The asker ends "the call" before anything of the counsellor's was registered.
    assertThat(observations.end(actor("share-asker", "user"), SOURCE, "asker-ended"))
        .isEqualTo(
            de.caritas.cob.userservice.api.service.matrix.MatrixCallObservationService.Outcome.OK);

    var counsellorActor = actor(counsellor.getId(), "consultant");
    assertThat(observations.register(counsellorActor, SOURCE, "asker-ended", counsellorMedia, true))
        .isEqualTo(
            de.caritas.cob.userservice.api.service.matrix.MatrixCallObservationService.Outcome.OK);
    assertThat(sessionBindings.bind(counsellorActor, SOURCE, "asker-ended", counsellorMedia, true))
        .isTrue();
    var taken = bindings.findBySourceRoomIdAndCallId(SOURCE, "asker-ended").orElseThrow();
    assertThat(taken.getMediaRoomId()).isEqualTo(counsellorMedia);
    assertThat(taken.getEndedAt()).isNull();
  }

  @Test
  void anUnassignedCounsellorsInviteRingsWithoutAuthority_ButTheyMayUseTheAssignedOnesBinding()
      throws Exception {
    String colleagueMedia = "!colleague-media:example";
    String colleagueId = colleague.getMatrixUserId();
    var members = List.of(ASKER, counsellor.getMatrixUserId(), colleagueId);
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(members));
    when(matrix.getRoomMembers(SOURCE)).thenReturn(Optional.of(members));
    when(matrix.getCallRoomBinding(colleagueMedia, colleagueId))
        .thenReturn(Optional.of(Map.of("call_id", "team-call", "source_room_id", SOURCE)));
    when(matrix.ensureAdminInRoom(anyString(), anyString())).thenReturn(true);
    when(matrix.loginAsUserAccessToken(colleagueId)).thenReturn("c-token");

    assertThat(invites.handle(SOURCE, plaintextInvite(colleagueId, "team-call", colleagueMedia)))
        .isTrue();
    assertThat(invitationsOf(counsellor.getId(), "team-call")).isPositive();
    inCall(colleagueMedia, colleagueId, System.currentTimeMillis() + 600000);
    assertThatThrownBy(
            () -> shareLinks.create(7L, colleague.getId(), SOURCE, "team-call", null, null))
        .isInstanceOf(NotFoundException.class);

    // The assigned counsellor's own call (the fixture's running binding): the colleague in it may
    // open it to guests.
    inCall(MEDIA, colleagueId, System.currentTimeMillis() + 600000);
    assertThat(shareLinks.create(7L, colleague.getId(), SOURCE, "share-call", null, null).link())
        .isNotNull();
  }

  /**
   * FE#2209 review: the link owner's authority ends when no authorised counsellor is in the call.
   */
  @Test
  void seatingStopsOnceEveryCounsellorHasLeftTheCall() throws Exception {
    var link = shareLinks.create(7L, counsellor.getId(), SOURCE, "share-call", null, null).link();
    var guest = users.findById("share-guest").orElseThrow();
    var knockId = shareLinks.join(link.getToken(), guest).knockId();
    var counsellorEntity = consultants.findById(counsellor.getId()).orElseThrow();
    transaction.executeWithoutResult(s -> knocks.admit(knockId, counsellorEntity));
    // The asker stays, so the call itself still runs; the counsellor left it.
    inCall(MEDIA, ASKER, System.currentTimeMillis() + 600000);
    inCall(MEDIA, counsellor.getMatrixUserId(), System.currentTimeMillis() - 1);
    new JdbcTemplate(dataSource)
        .update(
            "UPDATE matrix_call_device SET expires_at = ? WHERE sender_matrix_id = ?",
            System.currentTimeMillis() - 1,
            counsellor.getMatrixUserId());

    assertThatThrownBy(() -> shareLinks.join(link.getToken(), guest))
        .isInstanceOf(NotFoundException.class);
    verify(matrix, never()).inviteUserToRoom(eq(MEDIA), eq(GUEST), anyString());
  }

  /** Security review of #1487: declining an admitted knock takes the seat back too. */
  @Test
  void decliningAnAdmittedKnockRevokesTheSeat() {
    var policy = policyService();
    var link = shareLinks.create(7L, counsellor.getId(), SOURCE, "share-call", null, null).link();
    var guest = users.findById("share-guest").orElseThrow();
    var knockId = shareLinks.join(link.getToken(), guest).knockId();
    var counsellorEntity = consultants.findById(counsellor.getId()).orElseThrow();
    transaction.executeWithoutResult(s -> knocks.admit(knockId, counsellorEntity));
    assertThat(shareLinks.join(link.getToken(), guest).callAdmitted()).isTrue();

    transaction.executeWithoutResult(s -> knocks.decline(knockId, counsellorEntity));

    assertThat(policy.resolve(SOURCE, GUEST, MEDIA)).isEqualTo(CallMediaPolicy.denied());
    assertThatThrownBy(() -> shareLinks.join(link.getToken(), guest))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
  }

  @Test
  void aMissedHangUpStillEndsTheLink_AndNobodyKeepsWaiting() {
    var link = shareLinks.create(7L, counsellor.getId(), SOURCE, "share-call", null, null).link();
    var waiting = shareLinks.join(link.getToken(), users.findById("share-guest").orElseThrow());
    assertThat(waiting.callAdmitted()).isFalse();

    // No hang-up event arrives; only the call memberships run out.
    new JdbcTemplate(dataSource)
        .update("UPDATE matrix_call_device SET expires_at = ?", System.currentTimeMillis() - 1);
    sweep.sweep();

    assertThat(links.findById(link.getId()).orElseThrow().getStatus()).isEqualTo("EXPIRED");
    assertThat(knockRows.findById(waiting.knockId()).orElseThrow().getStatus())
        .isEqualTo(GroupChatJoinRequest.Status.CANCELLED);
    assertThatThrownBy(
            () -> shareLinks.join(link.getToken(), users.findById("share-guest-2").orElseThrow()))
        .isInstanceOf(CallShareLinkEndedException.class);
  }

  private MatrixCallGuestService callGuestService() {
    return callGuests;
  }

  private MockMvc mvc(String id, String role, Long tenant) {
    var actor = new AuthenticatedUser();
    actor.setUserId(id);
    actor.setTenantId(tenant == null ? 7L : tenant);
    actor.setRoles(java.util.Set.of(role));
    var controller = new CallShareLinkController(shareLinks, actor, mock(UserAccountService.class));
    ReflectionTestUtils.setField(controller, "appBaseUrl", "https://app.example/");
    return MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new ApiResponseEntityExceptionHandler())
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

  private String device() {
    return "_" + counsellor.getMatrixUserId() + "_device";
  }

  /** Records a MatrixRTC membership of {@code matrixId} in the call room, as the lifecycle does. */
  private void inCall(String media, String matrixId, long expiresAt) {
    transaction.executeWithoutResult(
        status -> {
          var binding = bindings.lockByMediaRoomId(media).orElseThrow();
          binding
              .getDevices()
              .put(
                  "_" + matrixId + "_device",
                  new MatrixCallDevice(
                      matrixId,
                      System.currentTimeMillis() - 100,
                      expiresAt,
                      true,
                      "$" + matrixId + expiresAt));
          bindings.save(binding);
        });
  }

  private Map<String, Object> leave() {
    return Map.of(
        "type",
        "org.matrix.msc3401.call.member",
        "sender",
        counsellor.getMatrixUserId(),
        "state_key",
        device(),
        "event_id",
        "$leave",
        "origin_server_ts",
        System.currentTimeMillis(),
        "content",
        Map.of());
  }

  private static User user(String id, String matrixId, Long tenant) {
    return User.builder()
        .userId(id)
        .username(id)
        .email(id + "@synthetic.oriso.test")
        .matrixUserId(matrixId)
        .tenantId(tenant)
        .encourage2fa(true)
        .magicLinkLoginEnabled(false)
        .languageCode(LanguageCode.de)
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
