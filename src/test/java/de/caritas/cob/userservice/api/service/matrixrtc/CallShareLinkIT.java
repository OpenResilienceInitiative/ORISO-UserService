package de.caritas.cob.userservice.api.service.matrixrtc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import de.caritas.cob.userservice.api.service.agencyinvitelink.CallShareLinkEndedException;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CallShareLinkService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CallShareLinkSweep;
import de.caritas.cob.userservice.api.service.agencyinvitelink.ConfiguredDirectInvitePolicy;
import de.caritas.cob.userservice.api.service.agencyinvitelink.ConfiguredTemporaryAccountPolicy;
import de.caritas.cob.userservice.api.service.agencyinvitelink.DirectCallKnockService;
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
  EventNotificationService.class,
  ConsultantDisplayNameResolver.class,
  EventNotificationDeduplicationWriter.class,
  MatrixCallLifecycleService.class,
  MatrixCallConversationResolver.class,
  MatrixCallGuestService.class,
  CallShareLinkService.class,
  CallShareLinkSweep.class,
  DirectCallKnockService.class,
  ConfiguredDirectInvitePolicy.class,
  ConfiguredTemporaryAccountPolicy.class,
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
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private javax.sql.DataSource dataSource;
  @MockitoBean private MatrixSynapseService matrix;
  @MockitoBean private MatrixFeedUpdateSignalService feedUpdateSignals;
  @MockitoBean private IdentityTombstoneService tombstones;
  @MockitoBean private TenantService tenantService;

  private final ObjectMapper json = new ObjectMapper();
  private TransactionTemplate transaction;
  private Consultant counsellor;
  private Consultant colleague;
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

    // Joins the call: sees the knock in the per-call list and lets the guest in.
    var members = List.of(ASKER, counsellor.getMatrixUserId(), colleague.getMatrixUserId());
    when(matrix.getCallRoomMembers(SOURCE)).thenReturn(Optional.of(members));
    when(matrix.getRoomMembers(SOURCE)).thenReturn(Optional.of(members));
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
