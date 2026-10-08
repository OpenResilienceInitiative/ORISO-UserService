package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Actual HTTP handlers and committed relational state; only current authentication is a port. */
@SpringBootTest(properties = {"multitenancy.enabled=true"})
@ActiveProfiles("testing")
class EnquiryRejectionControllerIT {
  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;
  @Autowired private ConsultantAgencyRepository agencies;
  @Autowired private SessionRepository sessions;
  @MockitoBean private AuthenticatedUser caller;

  @MockitoBean
  private de.caritas.cob.userservice.api.admin.service.tenant.TenantService tenantMetadata;

  @MockitoBean private de.caritas.cob.userservice.api.adapters.matrix.MatrixRoomClient matrixRooms;
  @MockitoBean private de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService matrix;

  @MockitoBean
  private de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient credentials;

  @MockitoBean
  private de.caritas.cob.userservice.api.service.matrix.MatrixFeedUpdateSignalService feedSignal;

  @Autowired private EnquiryRejectionRepository rejections;
  @Autowired private EventNotificationRepository events;
  @MockitoBean private de.caritas.cob.userservice.api.service.agency.AgencyService agencyMetadata;
  private MockMvc mvc;
  private String seekerId;
  private String actorId;
  private Long sessionId;
  private Long agencyMembershipId;

  @BeforeEach
  void submittedOrdinaryAgencyEnquiry() {
    TenantContext.setCurrentTenant(7L);
    seekerId = UUID.randomUUID().toString();
    actorId = UUID.randomUUID().toString();
    var seeker =
        users.save(
            User.builder()
                .userId(seekerId)
                .username(seekerId)
                .tenantId(7L)
                .email("seeker@synthetic.oriso.test")
                .matrixUserId("@seeker:synthetic.oriso.test")
                .encourage2fa(true)
                .magicLinkLoginEnabled(false)
                .languageCode(LanguageCode.de)
                .build());
    var actor = new Consultant();
    actor.setId(actorId);
    actor.setTenantId(7L);
    actor.setUsername(actorId);
    actor.setFirstName("Synthetic");
    actor.setLastName("Counsellor");
    actor.setMatrixUserId("@counsellor:synthetic.oriso.test");
    actor.setEmail("counsellor@synthetic.oriso.test");
    actor.setEncourage2fa(true);
    actor.setMagicLinkLoginEnabled(false);
    actor.setNotifyEnquiriesRepeating(false);
    actor.setNotifyNewChatMessageFromAdviceSeeker(false);
    actor.setCreateDate(LocalDateTime.now(java.time.ZoneOffset.UTC));
    actor.setUpdateDate(LocalDateTime.now(java.time.ZoneOffset.UTC));
    actor.setStatus(ConsultantStatus.CREATED);
    actor.setLanguageCode(LanguageCode.de);
    actor = consultants.saveAndFlush(actor);
    agencyMembershipId =
        agencies
            .save(
                ConsultantAgency.builder()
                    .consultant(actor)
                    .agencyId(70L)
                    .status(ConsultantAgencyStatus.CREATED)
                    .tenantId(7L)
                    .createDate(LocalDateTime.now(java.time.ZoneOffset.UTC))
                    .updateDate(LocalDateTime.now(java.time.ZoneOffset.UTC))
                    .build())
            .getId();
    var enquiry = new Session(seeker, 1, "12345", 70L, Session.SessionStatus.NEW, false);
    enquiry.setCreateDate(LocalDateTime.now(java.time.ZoneOffset.UTC));
    enquiry.setUpdateDate(LocalDateTime.now(java.time.ZoneOffset.UTC));
    enquiry.setTenantId(7L);
    enquiry.setLanguageCode(LanguageCode.de);
    enquiry.setConversationType(ConversationType.AGENCY_COUNSELLING);
    enquiry.setIsConsultantDirectlySet(false);
    enquiry.setSupervisionOptedOut(false);
    enquiry.setEnquiryMessageDate(LocalDateTime.now());
    enquiry.setMatrixRoomId("!ordinary-rejection:synthetic.oriso.test");
    sessionId = sessions.save(enquiry).getId();
    var tenantDto =
        new de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO();
    tenantDto.setSubdomain("synthetic");
    when(tenantMetadata.getRestrictedTenantData(7L)).thenReturn(tenantDto);
    when(caller.getUserId()).thenReturn(actorId);
    when(caller.getTenantId()).thenReturn(7L);
    when(caller.isConsultant()).thenReturn(true);
    when(caller.getRoles()).thenReturn(Set.of("consultant"));
    var operator =
        new de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO();
    operator.setMatrixUserId("@operator:synthetic.oriso.test");
    when(credentials.fetchMatrixCredentials(70L)).thenReturn(java.util.Optional.of(operator));
    when(matrix.loginAsUserAccessToken(operator.getMatrixUserId()))
        .thenReturn("synthetic-local-token");
    when(matrixRooms.closeRoomForMessagesVerified(anyString(), anyString(), anyString()))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              assertThat(sessions.findById(sessionId).orElseThrow().getStatus())
                  .isEqualTo(Session.SessionStatus.REJECTED);
              assertThat(rejections.findById(sessionId).orElseThrow().getState())
                  .isEqualTo(EnquiryRejection.State.PENDING);
              assertThat(
                      events.findByRecipientUserIdOrderByCreateDateDescIdDesc(
                          seekerId, org.springframework.data.domain.Pageable.unpaged()))
                  .isEmpty();
              return true;
            });
    when(agencyMetadata.getAgencies(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(java.util.List.of());
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @AfterEach
  void removeOnlyOwnFixture() {
    TenantContext.setCurrentTenant(7L);
    events.deleteAll(
        events.findByRecipientUserIdOrderByCreateDateDescIdDesc(
            seekerId, org.springframework.data.domain.Pageable.unpaged()));
    for (var identity : java.util.List.of(seekerId, actorId)) {
      events.deleteAll(
          events.findByRecipientUserIdOrderByCreateDateDescIdDesc(
              identity, org.springframework.data.domain.Pageable.unpaged()));
      jdbc.update("DELETE FROM account_inactivity_journal WHERE identity_id=?", identity);
      jdbc.update("DELETE FROM account_inactivity WHERE identity_id=?", identity);
    }
    if (sessionId != null) sessions.deleteById(sessionId);
    if (agencyMembershipId != null) agencies.deleteById(agencyMembershipId);
    if (actorId != null) consultants.deleteById(actorId);
    if (seekerId != null) users.deleteById(seekerId);
    TenantContext.clear();
  }

  @Test
  void agencyCounsellorCanRejectSubmittedUnassignedEnquiry() throws Exception {
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());
    assertThat(rejections.findById(sessionId).orElseThrow().getState())
        .isEqualTo(EnquiryRejection.State.CONFIRMED);
    assertThat(
            events.findByRecipientUserIdOrderByCreateDateDescIdDesc(
                seekerId, org.springframework.data.domain.Pageable.unpaged()))
        .hasSize(1)
        .first()
        .extracting(EventNotification::getEventType)
        .isEqualTo("request.denied");
  }

  @Test
  void failedClosureRemainsDurableWithoutAffirmativeFeed() throws Exception {
    org.mockito.Mockito.doReturn(false)
        .when(matrixRooms)
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isServiceUnavailable());
    var decision = rejections.findById(sessionId).orElseThrow();
    assertThat(decision.getState()).isEqualTo(EnquiryRejection.State.PENDING);
    assertThat(decision.getRecipientOutcome())
        .isEqualTo(EnquiryRejection.RecipientOutcome.UNDECIDED);
    assertThat(decision.getAttemptCount()).isEqualTo(1);
    assertThat(decision.getFailureStage()).isEqualTo("PRIMARY");
    assertThat(decision.getClaimToken()).isNull();
    assertThat(sessions.findById(sessionId).orElseThrow().getStatus())
        .isEqualTo(Session.SessionStatus.REJECTED);
    assertThat(feed()).isEmpty();
  }

  @Test
  void confirmedReplayPreservesOriginalDecisionAndSingletonFeed() throws Exception {
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());
    var original = rejections.findById(sessionId).orElseThrow();
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());
    var replay = rejections.findById(sessionId).orElseThrow();
    assertThat(replay.getActorId()).isEqualTo(original.getActorId());
    assertThat(replay.getRejectedAt()).isEqualTo(original.getRejectedAt());
    assertThat(replay.getGeneration()).isEqualTo(original.getGeneration());
    assertThat(replay.getAttemptCount()).isEqualTo(1);
    assertThat(feed()).hasSize(1);
    org.mockito.Mockito.verify(matrixRooms, org.mockito.Mockito.times(1))
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
  }

  @Test
  void currentSoftDeletedOriginalRecipientIsSuppressedWithoutEndlessClosureRetry()
      throws Exception {
    org.mockito.Mockito.doAnswer(
            call -> {
              var seeker = users.findById(seekerId).orElseThrow();
              seeker.setDeleteDate(LocalDateTime.now());
              users.save(seeker);
              return true;
            })
        .when(matrixRooms)
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());
    var decision = rejections.findById(sessionId).orElseThrow();
    assertThat(decision.getState()).isEqualTo(EnquiryRejection.State.CONFIRMED);
    assertThat(decision.getRecipientOutcome())
        .isEqualTo(EnquiryRejection.RecipientOutcome.SUPPRESSED);
    assertThat(feed()).isEmpty();
  }

  @Test
  void changedRoomBindingCannotConfirmAnotherConversation() throws Exception {
    org.mockito.Mockito.doAnswer(
            call -> {
              var session = sessions.findById(sessionId).orElseThrow();
              session.setMatrixRoomId("!replacement:synthetic.oriso.test");
              sessions.save(session);
              return true;
            })
        .when(matrixRooms)
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isConflict());
    assertThat(rejections.findById(sessionId).orElseThrow().getState())
        .isEqualTo(EnquiryRejection.State.PENDING);
    assertThat(feed()).isEmpty();
  }

  @Test
  void rejectedSeekerRetainsActualDetailAndListHistoryWhileUnrelatedUserCannotReadIt()
      throws Exception {
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());
    when(caller.isConsultant()).thenReturn(false);
    when(caller.getUserId()).thenReturn(seekerId);
    when(caller.getRoles()).thenReturn(Set.of("user"));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/users/sessions/room/{sessionId}", sessionId))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.sessions[0].session.status")
                .value(5));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/users/sessions/askers"))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.sessions[0].session.status")
                .value(5));
    var unrelatedId = UUID.randomUUID().toString();
    users.save(
        User.builder()
            .userId(unrelatedId)
            .email("unrelated@synthetic.oriso.test")
            .matrixUserId("@unrelated:synthetic.oriso.test")
            .username(unrelatedId)
            .tenantId(7L)
            .encourage2fa(true)
            .magicLinkLoginEnabled(false)
            .languageCode(LanguageCode.de)
            .build());
    try {
      when(caller.getUserId()).thenReturn(unrelatedId);
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                  "/users/sessions/room/{sessionId}", sessionId))
          .andExpect(status().isNoContent());
    } finally {
      users.deleteById(unrelatedId);
    }
  }

  @Test
  void originalRejectorCanReadOwnPendingCaseToRetryClosure() throws Exception {
    // The exact existing projection works for the valid submitted NEW control.
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/users/sessions/room/{sessionId}", sessionId))
        .andExpect(status().isOk());
    org.mockito.Mockito.doReturn(false)
        .when(matrixRooms)
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isServiceUnavailable());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/users/sessions/room/{sessionId}", sessionId))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.sessions[0].session.status")
                .value(5));
  }

  @Autowired
  private de.caritas.cob.userservice.api.facade.assignsession.SessionToConsultantVerifier
      assignmentVerifier;

  @Autowired private de.caritas.cob.userservice.api.service.session.SessionService sessionService;

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void persistedRejectionCannotBeAssignedEvenWithSkipFlag(boolean skip) throws Exception {
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());
    var dto =
        de.caritas.cob.userservice.api.facade.assignsession.ConsultantSessionDTO.builder()
            .session(sessions.findById(sessionId).orElseThrow())
            .consultant(consultants.findByIdAndDeleteDateIsNull(actorId).orElseThrow())
            .build();
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> assignmentVerifier.verifyPreconditionsForAssignment(dto, skip))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
    assertThat(sessions.findById(sessionId).orElseThrow().getConsultant()).isNull();
  }

  @Test
  void staleAssignmentAndRollbackCannotReopenCommittedRejection() throws Exception {
    var stale = sessions.findById(sessionId).orElseThrow();
    var consultant = consultants.findById(actorId).orElseThrow();
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                sessionService.updateConsultantAndStatusForSession(
                    stale, consultant, Session.SessionStatus.IN_PROGRESS))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                sessionService.updateConsultantAndStatusForSession(
                    stale, null, Session.SessionStatus.NEW))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
    assertThat(sessions.findById(sessionId).orElseThrow().getStatus())
        .isEqualTo(Session.SessionStatus.REJECTED);
  }

  @Test
  void canonicalSessionPurgeCascadesOnlyItsRejectionAudit() throws Exception {
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());
    assertThat(rejections.existsById(sessionId)).isTrue();
    sessions.deleteById(sessionId);
    assertThat(sessions.findById(sessionId)).isEmpty();
    assertThat(rejections.existsById(sessionId)).isFalse();
    sessionId = null;
  }

  @Autowired
  private de.caritas.cob.userservice.api.service.matrix.MatrixCallInviteNotificationService
      callInvites;

  @Autowired
  private de.caritas.cob.userservice.api.service.matrix.MatrixCallLifecycleService callLifecycle;

  @Autowired
  private de.caritas.cob.userservice.api.service.matrix.MatrixCallStateService callHistory;

  @Autowired private MatrixCallBindingRepository callBindings;

  @Autowired
  private de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService
      lifecycle;

  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired private TeamDiscussionRepository teamDiscussions;

  @Test
  void currentReadAccessCannotPublishANewMessageEventForRejectedHistory() throws Exception {
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());
    when(caller.isConsultant()).thenReturn(false);
    when(caller.getUserId()).thenReturn(seekerId);
    when(caller.getRoles()).thenReturn(Set.of("user"));
    mvc.perform(
            post("/users/event-notifications/message-events")
                .contentType("application/json")
                .content(
                    "{\"roomId\":\"!ordinary-rejection:synthetic.oriso.test\",\"messagePreview\":\"Synthetic blocked post\"}"))
        .andExpect(status().isForbidden());
    assertThat(feed()).hasSize(1);
  }

  @Test
  void primaryAndTeamMustBothBeVerifiedBeforeConfirmation() throws Exception {
    var team =
        teamDiscussions.saveAndFlush(
            TeamDiscussion.builder()
                .sessionId(sessionId)
                .matrixRoomId("!rejection-team:synthetic.oriso.test")
                .tenantId(7L)
                .createDate(LocalDateTime.now())
                .build());
    try {
      org.mockito.Mockito.doAnswer(call -> !team.getMatrixRoomId().equals(call.getArgument(0)))
          .when(matrixRooms)
          .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
      mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
          .andExpect(status().isServiceUnavailable());
      var decision = rejections.findById(sessionId).orElseThrow();
      assertThat(decision.getTeamRoomId()).isEqualTo(team.getMatrixRoomId());
      assertThat(decision.getState()).isEqualTo(EnquiryRejection.State.PENDING);
      assertThat(decision.getFailureStage()).isEqualTo("TEAM");
      assertThat(teamDiscussions.findById(team.getId()).orElseThrow().isReadOnlyApplied())
          .isFalse();
      assertThat(feed()).isEmpty();
    } finally {
      teamDiscussions.deleteById(team.getId());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(CallPhase.class)
  void rejectedStoredCallCannotInviteOrStartButOriginalCallHistoryRemainsReadable(CallPhase phase)
      throws Exception {
    boolean lateStart = phase != CallPhase.NEW_INVITE;
    var source = sessions.findById(sessionId).orElseThrow().getMatrixRoomId();
    var media = "!rejection-call-" + UUID.randomUUID() + ":synthetic.oriso.test";
    var callId = "rejection-" + UUID.randomUUID();
    long now = System.currentTimeMillis();
    lifecycle.assignAtCreation(seekerId, 7L, 12, 0L, java.time.Instant.now());
    lifecycle.assignAtCreation(actorId, 7L, 12, 0L, java.time.Instant.now());
    when(matrix.getCallRoomMembers(source))
        .thenReturn(
            java.util.Optional.of(
                java.util.List.of(
                    "@seeker:synthetic.oriso.test", "@counsellor:synthetic.oriso.test")));
    when(matrix.getCallRoomBinding(media, "@seeker:synthetic.oriso.test"))
        .thenReturn(
            java.util.Optional.of(java.util.Map.of("call_id", callId, "source_room_id", source)));
    when(matrix.ensureAdminInRoom(media, "@seeker:synthetic.oriso.test")).thenReturn(true);
    var invite =
        java.util.Map.<String, Object>of(
            "sender",
            "@seeker:synthetic.oriso.test",
            "event_id",
            "$synthetic-invite",
            "origin_server_ts",
            now,
            "content",
            java.util.Map.of(
                "call_id", callId, "call_room_id", media, "lifetime", 60000, "is_video", false));
    if (lateStart) assertThat(callInvites.handle(source, invite)).isTrue();
    if (phase == CallPhase.EXISTING_END) {
      callLifecycle.handleRoom(media, syntheticCallState(false));
      assertThat(callBindings.findByMediaRoomId(media).orElseThrow().getStartedAt()).isNotNull();
    }
    try {
      mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
          .andExpect(status().isNoContent());
      if (lateStart) {
        callLifecycle.handleRoom(media, syntheticCallState(phase == CallPhase.EXISTING_END));
        var retained = callBindings.findByMediaRoomId(media).orElseThrow();
        if (phase == CallPhase.EXISTING_END) assertThat(retained.getEndedAt()).isNotNull();
        else assertThat(retained.getStartedAt()).isNull();
        when(caller.isConsultant()).thenReturn(false);
        when(caller.getUserId()).thenReturn(seekerId);
        assertThat(callHistory.read(caller, source, callId)).isPresent();
      } else {
        org.mockito.Mockito.clearInvocations(matrix);
        assertThat(callInvites.handle(source, invite)).isFalse();
        org.mockito.Mockito.verifyNoInteractions(matrix);
        assertThat(callBindings.findByMediaRoomId(media)).isEmpty();
      }
      assertThat(feed())
          .extracting(EventNotification::getEventType)
          .containsExactly("request.denied");
    } finally {
      callBindings.findByMediaRoomId(media).ifPresent(callBindings::delete);
    }
  }

  private enum CallPhase {
    NEW_INVITE,
    LATE_START,
    EXISTING_END
  }

  private java.util.Map<String, Object> syntheticCallState(boolean departure) {
    var content =
        departure
            ? java.util.Map.<String, Object>of()
            : java.util.Map.<String, Object>of(
                "application",
                "m.call",
                "scope",
                "m.room",
                "call_id",
                "",
                "device_id",
                "device",
                "expires",
                60000,
                "focus_active",
                java.util.Map.of("type", "livekit"),
                "foci_preferred",
                java.util.List.of());
    var member =
        java.util.Map.<String, Object>of(
            "type",
            "org.matrix.msc3401.call.member",
            "sender",
            "@seeker:synthetic.oriso.test",
            "event_id",
            departure ? "$synthetic-departure" : "$synthetic-attendance",
            "state_key",
            "_@seeker:synthetic.oriso.test_device",
            "origin_server_ts",
            System.currentTimeMillis(),
            "content",
            content);
    return java.util.Map.of("state", java.util.Map.of("events", java.util.List.of(member)));
  }

  @Test
  void staleEncryptedEnquiryFinalizationCannotReopenARejectionCommittedDuringEventReadback() {
    var stale = sessions.findById(sessionId).orElseThrow();
    stale.setStatus(Session.SessionStatus.INITIAL);
    stale.setEnquiryMessageDate(null);
    sessions.save(stale);
    var original = users.findById(seekerId).orElseThrow();
    var facade =
        new de.caritas.cob.userservice.api.facade.CreateEnquiryMessageFacade(
            sessionService,
            org.mockito.Mockito.mock(
                de.caritas.cob.userservice.api.service.dpa.NewCounsellingDpaPolicy.class),
            matrix,
            org.mockito.Mockito.mock(
                de.caritas.cob.userservice.api.facade.EmailNotificationFacade.class),
            org.mockito.Mockito.mock(
                de.caritas.cob.userservice.api.service.ConsultantAgencyService.class),
            org.mockito.Mockito.mock(
                de.caritas.cob.userservice.api.service.consultingtype.TopicConsultantRoutingService
                    .class),
            org.mockito.Mockito.mock(
                de.caritas.cob.userservice.api.service.notification.EventNotificationService.class),
            org.mockito.Mockito.mock(
                de.caritas.cob.userservice.api.service.session.AgencyPreAssignmentRoomService
                    .class),
            org.mockito.Mockito.mock(
                de.caritas.cob.userservice.api.service.erstantwort.ErstantwortPayloadBuilder.class),
            org.mockito.Mockito.mock(
                de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService
                    .class));
    when(matrix.loginAsUserAccessToken(original.getMatrixUserId()))
        .thenReturn("synthetic-seeker-token");
    when(matrix.getRoomEvent(
            stale.getMatrixRoomId(), "$synthetic-enquiry", "synthetic-seeker-token"))
        .thenAnswer(
            call -> {
              // A competing finalization completed while this original readback was in flight.
              var submitted = sessions.findById(sessionId).orElseThrow();
              submitted.setStatus(Session.SessionStatus.NEW);
              submitted.setEnquiryMessageDate(LocalDateTime.now());
              sessions.save(submitted);
              mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
                  .andExpect(status().isNoContent());
              return java.util.Optional.of(
                  java.util.Map.<String, Object>of(
                      "event_id",
                      "$synthetic-enquiry",
                      "type",
                      "m.room.encrypted",
                      "sender",
                      original.getMatrixUserId()));
            });
    var enquiry = new EnquiryData(original, sessionId, null, "de");
    enquiry.setMatrixEventId("$synthetic-enquiry");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> facade.createEnquiryMessage(enquiry))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
    assertThat(sessions.findById(sessionId).orElseThrow().getStatus())
        .isEqualTo(Session.SessionStatus.REJECTED);
    assertThat(feed()).hasSize(1);
  }

  @MockitoBean
  private de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffects
      accountEffects;

  @Autowired
  private de.caritas.cob.userservice.api.service.enquiry.EnquiryRejectionService rejectionService;

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(InvalidRejection.class)
  void rejectsInvalidCurrentAuthorityOrEnquiryBeforeAnyProtocolEffect(InvalidRejection invalid)
      throws Exception {
    var current = sessions.findById(sessionId).orElseThrow();
    switch (invalid) {
      case UNSUBMITTED -> current.setEnquiryMessageDate(null);
      case INITIAL -> current.setStatus(Session.SessionStatus.INITIAL);
      case IN_PROGRESS -> current.setStatus(Session.SessionStatus.IN_PROGRESS);
      case DONE -> current.setStatus(Session.SessionStatus.DONE);
      case ARCHIVED -> current.setStatus(Session.SessionStatus.IN_ARCHIVE);
      case ANONYMOUS -> {
        sessions.deleteById(sessionId);
        current =
            new Session(
                users.findById(seekerId).orElseThrow(),
                1,
                "12345",
                70L,
                Session.SessionStatus.NEW,
                false);
        current.setCreateDate(LocalDateTime.now(java.time.ZoneOffset.UTC));
        current.setUpdateDate(LocalDateTime.now(java.time.ZoneOffset.UTC));
        current.setTenantId(7L);
        current.setLanguageCode(LanguageCode.de);
        current.setIsConsultantDirectlySet(false);
        current.setSupervisionOptedOut(false);
        current.setConversationType(ConversationType.AGENCY_COUNSELLING);
        current.setEnquiryMessageDate(LocalDateTime.now());
        current.setMatrixRoomId("!ordinary-rejection:synthetic.oriso.test");
        current.setRegistrationType(Session.RegistrationType.ANONYMOUS);
      }
      case LIVE -> current.setConversationType(ConversationType.LIVE_CHAT);
      case INTERNAL -> current.setConversationType(ConversationType.INTERNAL_GROUP);
      case SELF_HELP -> current.setConversationType(ConversationType.SELF_HELP);
      case ASSIGNED -> current.setConsultant(consultants.findById(actorId).orElseThrow());
      case WRONG_AGENCY -> current.setAgencyId(71L);
      case REMOVED_AGENCY -> {
        var link = agencies.findById(agencyMembershipId).orElseThrow();
        link.setDeleteDate(LocalDateTime.now());
        agencies.save(link);
      }
      case DELETED_ACTOR -> {
        var actor = consultants.findById(actorId).orElseThrow();
        actor.setDeleteDate(LocalDateTime.now());
        consultants.saveAndFlush(actor);
      }
      case INACTIVE_ACTOR -> {
        lifecycle.assignAtCreation(actorId, 7L, 12, 0L, java.time.Instant.now());
        when(accountEffects.suspend(actorId)).thenReturn(true);
        assertThat(lifecycle.suspend(actorId)).isTrue();
      }
      case FOREIGN_ACTOR -> {
        var actor = consultants.findById(actorId).orElseThrow();
        actor.setTenantId(8L);
        consultants.saveAndFlush(actor);
      }
      case WRONG_ROLE -> when(caller.isConsultant()).thenReturn(false);
      case WRONG_TENANT -> when(caller.getTenantId()).thenReturn(8L);
    }
    sessionId = sessions.save(current).getId();
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(
            status().is(invalid.ordinal() >= InvalidRejection.WRONG_AGENCY.ordinal() ? 403 : 409));
    assertThat(rejections.findById(sessionId)).isEmpty();
    assertThat(feed()).isEmpty();
    org.mockito.Mockito.verifyNoInteractions(matrixRooms, credentials);
    if (invalid == InvalidRejection.FOREIGN_ACTOR) {
      TenantContext.runIn(
          0L,
          () -> {
            var actor = consultants.findById(actorId).orElseThrow();
            actor.setTenantId(7L);
            consultants.saveAndFlush(actor);
          });
    }
  }

  private enum InvalidRejection {
    UNSUBMITTED,
    INITIAL,
    IN_PROGRESS,
    DONE,
    ARCHIVED,
    ANONYMOUS,
    LIVE,
    INTERNAL,
    SELF_HELP,
    ASSIGNED,
    WRONG_AGENCY,
    REMOVED_AGENCY,
    DELETED_ACTOR,
    INACTIVE_ACTOR,
    FOREIGN_ACTOR,
    WRONG_ROLE,
    WRONG_TENANT
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void freshIneligibleRecipientStillConfirmsVerifiedClosureWithoutRedirect(boolean foreignTenant)
      throws Exception {
    org.mockito.Mockito.doAnswer(
            call -> {
              if (foreignTenant) {
                var current = users.findById(seekerId).orElseThrow();
                current.setTenantId(8L);
                users.save(current);
              } else {
                lifecycle.assignAtCreation(seekerId, 7L, 12, 0L, java.time.Instant.now());
                when(accountEffects.suspend(seekerId)).thenReturn(true);
                assertThat(lifecycle.suspend(seekerId)).isTrue();
              }
              return true;
            })
        .when(matrixRooms)
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());
    assertThat(rejections.findById(sessionId).orElseThrow().getRecipientOutcome())
        .isEqualTo(EnquiryRejection.RecipientOutcome.SUPPRESSED);
    assertThat(feed()).isEmpty();
    if (foreignTenant)
      TenantContext.runIn(
          0L,
          () -> {
            var current = users.findById(seekerId).orElseThrow();
            current.setTenantId(7L);
            users.save(current);
          });
  }

  @Test
  void concurrentSameActorReplayUsesTheDurableClaimWithoutDuplicatingRemoteTraffic()
      throws Exception {
    org.mockito.Mockito.doAnswer(
            call -> {
              // A second replica sees TX1 committed while the first remote operation is in flight.
              mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
                  .andExpect(status().isServiceUnavailable());
              assertThat(rejections.findById(sessionId).orElseThrow().getAttemptCount())
                  .isEqualTo(1);
              assertThat(feed()).isEmpty();
              return true;
            })
        .when(matrixRooms)
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());
    assertThat(feed()).hasSize(1);
    org.mockito.Mockito.verify(matrixRooms, org.mockito.Mockito.times(1))
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
  }

  @Test
  void rolledBackConfirmationCannotPublishFeedOrLoseTheDurablePendingDecision() throws Exception {
    jdbc.execute(
        "ALTER TABLE event_notification ADD CONSTRAINT n12_feed_storage_fault CHECK (event_type <> 'request.denied')");
    try {
      mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
          .andExpect(status().isConflict());
      var pending = rejections.findById(sessionId).orElseThrow();
      assertThat(pending.getState()).isEqualTo(EnquiryRejection.State.PENDING);
      assertThat(pending.getRecipientOutcome())
          .isEqualTo(EnquiryRejection.RecipientOutcome.UNDECIDED);
      assertThat(feed()).isEmpty();
      org.mockito.Mockito.verifyNoInteractions(feedSignal);
    } finally {
      jdbc.execute("ALTER TABLE event_notification DROP CONSTRAINT n12_feed_storage_fault");
    }
  }

  @Test
  void anotherReplicaCanRepairAnExpiredClaimAndReplayExactlyOneCommittedOutcome() throws Exception {
    org.mockito.Mockito.doReturn(false)
        .when(matrixRooms)
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isServiceUnavailable());
    var original = rejections.findById(sessionId).orElseThrow();
    assertThat(rejectionService.repair(sessionId)).isFalse();
    original.setClaimToken("crashed-task");
    original.setClaimUntil(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
    original.setNextAttemptAt(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
    rejections.saveAndFlush(original);
    org.mockito.Mockito.doReturn(true)
        .when(matrixRooms)
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
    var fresh =
        new de.caritas.cob.userservice.api.service.enquiry.EnquiryRejectionService(
            context.getBean(
                de.caritas.cob.userservice.api.service.enquiry.EnquiryRejectionTransactions.class),
            credentials,
            matrix,
            matrixRooms);
    assertThat(fresh.repair(sessionId)).isTrue();
    assertThat(fresh.repair(sessionId)).isTrue();
    var repaired = rejections.findById(sessionId).orElseThrow();
    assertThat(repaired.getGeneration()).isEqualTo(original.getGeneration());
    assertThat(repaired.getRejectedAt()).isEqualTo(original.getRejectedAt());
    assertThat(repaired.getActorId()).isEqualTo(original.getActorId());
    assertThat(repaired.getAttemptCount()).isEqualTo(2);
    assertThat(feed()).hasSize(1);
    org.mockito.Mockito.verify(matrixRooms, org.mockito.Mockito.times(2))
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
  }

  @Test
  void boundedBackgroundRepairRestoresTenantAndConfirmsOnlyTheOriginalSnapshot() throws Exception {
    org.mockito.Mockito.doReturn(false)
        .when(matrixRooms)
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isServiceUnavailable());
    var pending = rejections.findById(sessionId).orElseThrow();
    pending.setNextAttemptAt(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
    rejections.saveAndFlush(pending);
    org.mockito.Mockito.doReturn(true)
        .when(matrixRooms)
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
    var scheduler =
        new de.caritas.cob.userservice.api.service.enquiry.EnquiryRejectionRepairScheduler(
            rejections,
            rejectionService,
            context.getBean(
                de.caritas.cob.userservice.api.service.enquiry.EnquiryRejectionTransactions.class),
            context.getBean(java.time.Clock.class));
    TenantContext.setCurrentTenant(99L);
    scheduler.retryPending();
    assertThat(TenantContext.getCurrentTenant()).isEqualTo(99L);
    TenantContext.setCurrentTenant(7L);
    assertThat(rejections.findById(sessionId).orElseThrow().getState())
        .isEqualTo(EnquiryRejection.State.CONFIRMED);
    assertThat(feed()).hasSize(1);
    scheduler.retryPending();
    org.mockito.Mockito.verify(matrixRooms, org.mockito.Mockito.times(2))
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
  }

  @Test
  void staleBackgroundSnapshotIsDeferredWithoutRedirectingRoomsOrLosingTenantContext()
      throws Exception {
    org.mockito.Mockito.doReturn(false)
        .when(matrixRooms)
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isServiceUnavailable());
    var pending = rejections.findById(sessionId).orElseThrow();
    pending.setNextAttemptAt(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
    rejections.saveAndFlush(pending);
    var changed = sessions.findById(sessionId).orElseThrow();
    changed.setMatrixRoomId("!different:synthetic.oriso.test");
    sessions.save(changed);
    var scheduler =
        new de.caritas.cob.userservice.api.service.enquiry.EnquiryRejectionRepairScheduler(
            rejections,
            rejectionService,
            context.getBean(
                de.caritas.cob.userservice.api.service.enquiry.EnquiryRejectionTransactions.class),
            context.getBean(java.time.Clock.class));
    TenantContext.setCurrentTenant(99L);
    scheduler.retryPending();
    assertThat(TenantContext.getCurrentTenant()).isEqualTo(99L);
    TenantContext.setCurrentTenant(7L);
    var deferred = rejections.findById(sessionId).orElseThrow();
    assertThat(deferred.getState()).isEqualTo(EnquiryRejection.State.PENDING);
    assertThat(deferred.getPrimaryRoomId()).isEqualTo(pending.getPrimaryRoomId());
    assertThat(deferred.getFailureStage()).isEqualTo("BINDING");
    assertThat(deferred.getNextAttemptAt()).isAfter(LocalDateTime.now(java.time.ZoneOffset.UTC));
    assertThat(feed()).isEmpty();
    org.mockito.Mockito.verify(matrixRooms, org.mockito.Mockito.times(1))
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void rejectionCommittedDuringRemoteCallLookupPreventsLateInvitationOrStart(boolean lateStart)
      throws Exception {
    var source = sessions.findById(sessionId).orElseThrow().getMatrixRoomId();
    var media = "!inflight-call-" + UUID.randomUUID() + ":synthetic.oriso.test";
    var callId = "inflight-" + UUID.randomUUID();
    lifecycle.assignAtCreation(seekerId, 7L, 12, 0L, java.time.Instant.now());
    lifecycle.assignAtCreation(actorId, 7L, 12, 0L, java.time.Instant.now());
    var memberList =
        java.util.Optional.of(
            java.util.List.of("@seeker:synthetic.oriso.test", "@counsellor:synthetic.oriso.test"));
    when(matrix.getCallRoomMembers(source)).thenReturn(memberList);
    when(matrix.getCallRoomBinding(media, "@seeker:synthetic.oriso.test"))
        .thenReturn(
            java.util.Optional.of(java.util.Map.of("call_id", callId, "source_room_id", source)));
    when(matrix.ensureAdminInRoom(media, "@seeker:synthetic.oriso.test")).thenReturn(true);
    var invite =
        java.util.Map.<String, Object>of(
            "sender",
            "@seeker:synthetic.oriso.test",
            "event_id",
            "$inflight-invite",
            "origin_server_ts",
            System.currentTimeMillis(),
            "content",
            java.util.Map.of(
                "call_id", callId, "call_room_id", media, "lifetime", 60000, "is_video", false));
    if (lateStart) assertThat(callInvites.handle(source, invite)).isTrue();
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      org.mockito.Mockito.doAnswer(
              call -> {
                // Independent HTTP transaction commits while the original Matrix lookup has stale
                // context.
                executor
                    .submit(
                        () -> {
                          TenantContext.setCurrentTenant(7L);
                          try {
                            mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
                                .andExpect(status().isNoContent());
                          } finally {
                            TenantContext.clear();
                          }
                          return null;
                        })
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
                return memberList;
              })
          .when(matrix)
          .getCallRoomMembers(source);
      if (lateStart) {
        callLifecycle.handleRoom(media, syntheticCallState(false));
        assertThat(callBindings.findByMediaRoomId(media).orElseThrow().getStartedAt()).isNull();
      } else {
        assertThat(callInvites.handle(source, invite)).isFalse();
        assertThat(callBindings.findByMediaRoomId(media)).isEmpty();
      }
      assertThat(feed())
          .singleElement()
          .extracting(EventNotification::getEventType)
          .isEqualTo("request.denied");
      assertThat(
              events.findByRecipientUserIdOrderByCreateDateDescIdDesc(
                  actorId, org.springframework.data.domain.Pageable.unpaged()))
          .filteredOn(event -> "call.started".equals(event.getEventType()))
          .isEmpty();
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      callBindings.findByMediaRoomId(media).ifPresent(callBindings::delete);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {401, 403, 204})
  void realSecurityChainRequiresAuthenticationAndConsultantAuthority(int expected)
      throws Exception {
    var secured =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(
                org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                    .springSecurity())
            .build();
    lifecycle.assignAtCreation(actorId, 7L, 12, 0L, java.time.Instant.now());
    var request =
        post("/users/sessions/{sessionId}/rejection", sessionId)
            .cookie(new jakarta.servlet.http.Cookie("CSRF-TOKEN", "n12"))
            .header("X-CSRF-Token", "n12")
            .header("tenantId", "7");
    if (expected != 401)
      request.with(
          org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
              .jwt()
              .jwt(
                  jwt ->
                      jwt.subject(actorId)
                          .claim("tenantId", 7L)
                          .claim(
                              "realm_access",
                              java.util.Map.of("roles", java.util.List.of("consultant"))))
              .authorities(
                  new org.springframework.security.core.authority.SimpleGrantedAuthority(
                      expected == 204
                          ? de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue
                              .CONSULTANT_DEFAULT
                          : de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue
                              .USER_DEFAULT)));
    secured.perform(request).andExpect(status().is(expected));
    TenantContext.setCurrentTenant(7L);
    if (expected != 204) {
      assertThat(rejections.findById(sessionId)).isEmpty();
      org.mockito.Mockito.verifyNoInteractions(matrixRooms);
    }
  }

  @Test
  void terminalRejectionCannotBeAcceptedOrAssignedThroughExistingPublicHandlers() throws Exception {
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/users/sessions/new/{sessionId}", sessionId))
        .andExpect(status().isConflict());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/users/sessions/{sessionId}/consultant/{consultantId}", sessionId, actorId))
        .andExpect(status().isForbidden());
    assertThat(sessions.findById(sessionId).orElseThrow().getStatus())
        .isEqualTo(Session.SessionStatus.REJECTED);
    assertThat(sessions.findById(sessionId).orElseThrow().getConsultant()).isNull();
  }

  @Test
  void existingArchiveAndReopenCommandsCannotChangeTerminalRejection() throws Exception {
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/users/sessions/{sessionId}/archive", sessionId))
        .andExpect(status().isForbidden());
    when(caller.isConsultant()).thenReturn(false);
    when(caller.getUserId()).thenReturn(seekerId);
    when(caller.getRoles()).thenReturn(Set.of("user"));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/users/sessions/{sessionId}/dearchive", sessionId))
        .andExpect(status().isConflict());
    assertThat(sessions.findById(sessionId).orElseThrow().getStatus())
        .isEqualTo(Session.SessionStatus.REJECTED);
    assertThat(feed()).hasSize(1);
  }

  @Test
  void canonicalTenantOneLegacySessionNullUsesOnlyCurrentTenantOneIdentities() throws Exception {
    TenantContext.runIn(
        0L,
        () -> {
          var actor = consultants.findById(actorId).orElseThrow();
          actor.setTenantId(1L);
          consultants.saveAndFlush(actor);
          var owner = users.findById(seekerId).orElseThrow();
          owner.setTenantId(1L);
          users.save(owner);
          var membership = agencies.findById(agencyMembershipId).orElseThrow();
          membership.setTenantId(1L);
          agencies.save(membership);
          jdbc.update("UPDATE session SET tenant_id=NULL WHERE id=?", sessionId);
        });
    TenantContext.setCurrentTenant(1L);
    when(caller.getTenantId()).thenReturn(1L);
    try {
      assertThat(sessions.findById(sessionId).orElseThrow().getTenantId()).isNull();
      mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
          .andExpect(status().isNoContent());
      assertThat(rejections.findById(sessionId).orElseThrow().getTenantId()).isEqualTo(1L);
      assertThat(feed()).singleElement().extracting(EventNotification::getTenantId).isEqualTo(1L);
    } finally {
      events.deleteAll(feed());
      TenantContext.runIn(
          0L,
          () -> {
            var actor = consultants.findById(actorId).orElseThrow();
            actor.setTenantId(7L);
            consultants.saveAndFlush(actor);
            var owner = users.findById(seekerId).orElseThrow();
            owner.setTenantId(7L);
            users.save(owner);
            var membership = agencies.findById(agencyMembershipId).orElseThrow();
            membership.setTenantId(7L);
            agencies.save(membership);
            jdbc.update("UPDATE session SET tenant_id=7 WHERE id=?", sessionId);
          });
      TenantContext.setCurrentTenant(7L);
    }
  }

  private java.util.List<EventNotification> feed() {
    return events.findByRecipientUserIdOrderByCreateDateDescIdDesc(
        seekerId, org.springframework.data.domain.Pageable.unpaged());
  }

  @Test
  void canonicalLegacyNullConversationIsReadableAndCanBeRejected() throws Exception {
    var session = sessions.findById(sessionId).orElseThrow();
    session.setConversationType(null);
    sessions.save(session);
    when(caller.isConsultant()).thenReturn(false);
    when(caller.getUserId()).thenReturn(seekerId);
    when(caller.getRoles()).thenReturn(Set.of("user"));
    var response =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/users/sessions/room/{sessionId}", sessionId))
            .andExpect(status().isOk())
            .andReturn();
    var body =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(response.getResponse().getContentAsString());
    var projected = body.path("sessions").get(0).path("session");
    assertThat(projected.path("status").asInt()).isEqualTo(1);
    assertThat(projected.path("registrationType").asText()).isEqualTo("REGISTERED");
    assertThat(projected.has("conversationType")).isTrue();
    assertThat(projected.get("conversationType").isNull()).isTrue();
    System.out.println(
        "N12_CANONICAL_LEGACY_READ: conversationType=null, status=1, registrationType=REGISTERED, agencyId=70");
    when(caller.isConsultant()).thenReturn(true);
    when(caller.getUserId()).thenReturn(actorId);
    when(caller.getRoles()).thenReturn(Set.of("consultant"));
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());
  }
}
