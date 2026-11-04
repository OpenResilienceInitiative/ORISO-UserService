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
@SpringBootTest(
    properties = {
      "multitenancy.enabled=true",
      "matrix.feedSignal.enabled=true",
      "matrix.feedSignal.coalesceMillis=10"
    })
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

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
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
    org.mockito.Mockito.doNothing().when(feedSignal).signalFeedUpdated(anyString());
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
    tenantDto.setActive(true);
    when(tenantMetadata.getRestrictedTenantData(7L)).thenReturn(tenantDto);
    // HttpTenantFilter admits the request only after a fresh operational-status lookup.
    tenantDto.setActive(true);
    when(tenantMetadata.getRestrictedTenantDataFresh(7L)).thenReturn(tenantDto);
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

  @Autowired
  private de.caritas.cob.userservice.api.service.session.SessionOwnershipService ownership;

  @Test
  void canonicalOwnershipWriterCannotAssignCommittedRejectedEnquiry() throws Exception {
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());
    var rejected = sessions.findById(sessionId).orElseThrow();
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                ownership.updateOwnerAndStatus(
                    rejected,
                    consultants.findById(actorId).orElseThrow(),
                    Session.SessionStatus.IN_PROGRESS))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
    var current = sessions.findById(sessionId).orElseThrow();
    assertThat(current.getStatus()).isEqualTo(Session.SessionStatus.REJECTED);
    assertThat(current.getConsultant()).isNull();
    assertThat(current.getOwnershipRevision()).isEqualTo(rejected.getOwnershipRevision());
    assertThat(feed()).hasSize(1);
  }

  @Test
  void canonicalCompensationTokenFromBeforeRejectionCannotReopenCommittedDecision()
      throws Exception {
    var before = sessions.findById(sessionId).orElseThrow();
    var previous = ownership.updateOwnerAndStatus(before, null, Session.SessionStatus.NEW);
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());

    assertThat(
            ownership.compensateOwnerChange(
                sessionId, previous, null, Session.SessionStatus.NEW, before.getUpdateDate(), true))
        .isFalse();
    var current = sessions.findById(sessionId).orElseThrow();
    assertThat(current.getStatus()).isEqualTo(Session.SessionStatus.REJECTED);
    assertThat(current.getAgencyId()).isEqualTo(70L);
    assertThat(current.getConsultant()).isNull();
    assertThat(rejections.findById(sessionId).orElseThrow().getState())
        .isEqualTo(EnquiryRejection.State.CONFIRMED);
    assertThat(feed()).hasSize(1);
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
  void confirmedRejectionSignalsTheCommittedFeedOnceInTheOriginalTenant() throws Exception {
    record Receipt(
        String type, String matrixId, Object content, Long tenant, String thread, long rows) {}
    var received = new java.util.concurrent.CopyOnWriteArrayList<Receipt>();
    when(matrix.sendToDeviceMessage(
            anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap()))
        .thenAnswer(
            call -> {
              received.add(
                  new Receipt(
                      call.getArgument(0),
                      call.getArgument(1),
                      call.getArgument(2),
                      TenantContext.getCurrentTenant(),
                      Thread.currentThread().getName(),
                      feed().size()));
              return true;
            });
    org.mockito.Mockito.doCallRealMethod().when(feedSignal).signalFeedUpdated(seekerId);

    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(received).hasSize(1));
    assertThat(received.getFirst())
        .satisfies(
            receipt -> {
              assertThat(receipt.type()).isEqualTo("org.oriso.feed.updated");
              assertThat(receipt.matrixId()).isEqualTo("@seeker:synthetic.oriso.test");
              assertThat(receipt.content()).isEqualTo(java.util.Map.of());
              assertThat(receipt.tenant()).isEqualTo(7L);
              assertThat(receipt.thread()).startsWith("feed-signal-");
              assertThat(receipt.rows()).isEqualTo(1);
            });

    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());
    org.awaitility.Awaitility.await()
        .during(java.time.Duration.ofMillis(300))
        .atMost(java.time.Duration.ofSeconds(3))
        .untilAsserted(() -> assertThat(received).hasSize(1));
    assertThat(feed()).hasSize(1);
  }

  @Test
  void rolledBackFeedInsertSendsNoSignalAndLeavesTheCommittedDecisionPending() {
    org.mockito.Mockito.doCallRealMethod().when(feedSignal).signalFeedUpdated(seekerId);
    var attempt = rejectionTransactions.begin(sessionId);
    var transaction =
        new org.springframework.transaction.support.TransactionTemplate(
            context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
    transaction.executeWithoutResult(
        status -> {
          assertThat(
                  rejectionTransactions.finish(
                      sessionId, attempt.decision().getGeneration(), attempt.token(), true, null))
              .isTrue();
          assertThat(feed()).hasSize(1);
          status.setRollbackOnly();
        });

    org.awaitility.Awaitility.await()
        .during(java.time.Duration.ofMillis(300))
        .atMost(java.time.Duration.ofSeconds(3))
        .untilAsserted(
            () ->
                org.mockito.Mockito.verify(matrix, org.mockito.Mockito.never())
                    .sendToDeviceMessage(
                        anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap()));
    assertThat(feed()).isEmpty();
    assertThat(rejections.findById(sessionId).orElseThrow().getState())
        .isEqualTo(EnquiryRejection.State.PENDING);
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
  void confirmedRejectedTeamEnquiryDoesNotExposeMessagesThroughAgencyCredentials()
      throws Exception {
    var current = sessions.findById(sessionId).orElseThrow();
    current.setTeamSession(true);
    sessions.save(current);
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());
    org.mockito.Mockito.clearInvocations(credentials, matrix);
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/matrix/sessions/{sessionId}/messages", sessionId))
        .andExpect(status().isForbidden());
    org.mockito.Mockito.verifyNoInteractions(credentials, matrix);
  }

  @Test
  void originalRejectorCanReadOwnPendingCaseToRetryClosure() throws Exception {
    var current = sessions.findById(sessionId).orElseThrow();
    current.setTeamSession(true);
    sessions.save(current);
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
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/matrix/sessions/{sessionId}/messages", sessionId))
        .andExpect(status().isOk());
  }

  @Autowired
  private de.caritas.cob.userservice.api.facade.assignsession.SessionToConsultantVerifier
      assignmentVerifier;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private de.caritas.cob.userservice.api.service.session.SessionService sessionService;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private de.caritas.cob.userservice.api.service.enquiry.EnquiryRejectionTransactions
      rejectionTransactions;

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
  void tenantlessCallerInMultitenantModeCannotUseTheSingleTenantFallback() throws Exception {
    when(caller.getTenantId()).thenReturn(null);
    when(caller.getUserId()).thenReturn(seekerId);
    when(caller.isConsultant()).thenReturn(false);
    when(caller.getRoles()).thenReturn(Set.of("user"));
    when(tenantMetadata.getSingleTenancyTenantDataFresh())
        .thenReturn(
            new de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO()
                .id(7L));
    mvc.perform(
            post("/users/event-notifications/message-events")
                .contentType("application/json")
                .content(
                    "{\"roomId\":\"!ordinary-rejection:synthetic.oriso.test\",\"messagePreview\":\"Synthetic message\"}"))
        .andExpect(status().isForbidden());
    org.mockito.Mockito.verify(tenantMetadata, org.mockito.Mockito.never())
        .getSingleTenancyTenantDataFresh();
    assertThat(feed()).isEmpty();
  }

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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "false,GET,false", "false,POST,false", "false,RESTORE,false", "true,GET,false",
        "true,POST,false", "true,RESTORE,false",
    "false,GET,true", "false,POST,true", "false,RESTORE,true", "true,GET,true", "true,POST,true",
        "true,RESTORE,true"
  })
  void rejectionFreezesNewReadersButPreservesEstablishedTeamReaders(
      boolean confirmed, String operation, boolean recorded) throws Exception {
    var team =
        teamDiscussions.saveAndFlush(
            TeamDiscussion.builder()
                .sessionId(sessionId)
                .matrixRoomId("!frozen-team:synthetic.oriso.test")
                .tenantId(7L)
                .createDate(LocalDateTime.now())
                .build());
    if (recorded) {
      teamParticipants.saveAndFlush(
          TeamDiscussionParticipant.builder()
              .teamDiscussionId(team.getId())
              .consultantId(actorId)
              .joinDate(LocalDateTime.now().minusDays(1))
              .build());
    }
    org.mockito.Mockito.doReturn(confirmed)
        .when(matrixRooms)
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
    when(matrix.getRoomMembers(team.getMatrixRoomId()))
        .thenReturn(java.util.Optional.of(java.util.List.of("@counsellor:synthetic.oriso.test")));
    try {
      mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
          .andExpect(confirmed ? status().isNoContent() : status().isServiceUnavailable());
      org.mockito.Mockito.clearInvocations(matrix);
      if (operation.equals("RESTORE")) {
        org.assertj.core.api.ThrowableAssert.ThrowingCallable restore =
            () ->
                context
                    .getBean(de.caritas.cob.userservice.api.facade.TeamDiscussionFacade.class)
                    .restoreExistingDiscussionMembership(team.getId(), actorId);
        if (recorded)
          org.assertj.core.api.Assertions.assertThatCode(restore).doesNotThrowAnyException();
        else
          org.assertj.core.api.Assertions.assertThatThrownBy(restore)
              .isInstanceOf(
                  de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
      } else {
        var request =
            operation.equals("GET")
                ? org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/users/sessions/{sessionId}/team-discussion", sessionId)
                : post("/users/sessions/{sessionId}/team-discussion", sessionId);
        mvc.perform(request).andExpect(recorded ? status().isOk() : status().isForbidden());
      }
      if (!recorded) org.mockito.Mockito.verifyNoInteractions(matrix);
      assertThat(teamParticipants.findByTeamDiscussionId(team.getId())).hasSize(recorded ? 1 : 0);
    } finally {
      teamParticipants.deleteAll(teamParticipants.findByTeamDiscussionId(team.getId()));
      teamDiscussions.deleteById(team.getId());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "false,false",
    "false,true",
    "true,false",
    "true,true"
  })
  void departedEstablishedReaderWaitsForVerifiedClosureBeforeRejoining(
      boolean confirmed, boolean membershipUnavailable) throws Exception {
    var team =
        teamDiscussions.saveAndFlush(
            TeamDiscussion.builder()
                .sessionId(sessionId)
                .tenantId(7L)
                .matrixRoomId("!departed-team:synthetic.oriso.test")
                .createDate(LocalDateTime.now())
                .build());
    teamParticipants.saveAndFlush(
        TeamDiscussionParticipant.builder()
            .teamDiscussionId(team.getId())
            .consultantId(actorId)
            .joinDate(LocalDateTime.now().minusDays(1))
            .build());
    org.mockito.Mockito.doReturn(confirmed)
        .when(matrixRooms)
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
    when(matrix.getRoomMembers(team.getMatrixRoomId()))
        .thenReturn(
            membershipUnavailable
                ? java.util.Optional.empty()
                : java.util.Optional.of(java.util.List.of()));
    when(matrix.loginAsUserAccessToken("@counsellor:synthetic.oriso.test"))
        .thenReturn("synthetic-counsellor-token");
    when(matrix.joinRoom(team.getMatrixRoomId(), "synthetic-counsellor-token")).thenReturn(true);
    try {
      mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
          .andExpect(confirmed ? status().isNoContent() : status().isServiceUnavailable());
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                  "/users/sessions/{sessionId}/team-discussion", sessionId))
          .andExpect(status().isOk());
      mvc.perform(post("/users/sessions/{sessionId}/team-discussion", sessionId))
          .andExpect(confirmed ? status().isOk() : status().isServiceUnavailable());
      if (!confirmed) {
        org.mockito.Mockito.verify(matrix, org.mockito.Mockito.never())
            .inviteUserToRoom(
                org.mockito.ArgumentMatchers.eq(team.getMatrixRoomId()), anyString(), anyString());
        org.mockito.Mockito.verify(matrix, org.mockito.Mockito.never())
            .joinRoom(org.mockito.ArgumentMatchers.eq(team.getMatrixRoomId()), anyString());
      }
      assertThat(teamParticipants.findByTeamDiscussionId(team.getId())).hasSize(1);
    } finally {
      teamParticipants.deleteAll(teamParticipants.findByTeamDiscussionId(team.getId()));
      teamDiscussions.deleteById(team.getId());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void existingTeamAdmissionAndRejectionSerializeOnTheSameSession(boolean rejectionWins)
      throws Exception {
    var team =
        teamDiscussions.saveAndFlush(
            TeamDiscussion.builder()
                .sessionId(sessionId)
                .tenantId(7L)
                .matrixRoomId("!racing-team:synthetic.oriso.test")
                .createDate(LocalDateTime.now())
                .build());
    var joinEntered = new java.util.concurrent.CountDownLatch(1);
    var rejectEntered = new java.util.concurrent.CountDownLatch(1);
    var secondRequestStarted = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var closed = new java.util.concurrent.atomic.AtomicInteger();
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    when(matrix.getRoomMembers(team.getMatrixRoomId()))
        .thenReturn(java.util.Optional.of(java.util.List.of()));
    when(matrix.loginAsUserAccessToken("@counsellor:synthetic.oriso.test"))
        .thenReturn("synthetic-counsellor-token");
    when(matrix.joinRoom(team.getMatrixRoomId(), "synthetic-counsellor-token"))
        .thenAnswer(
            call -> {
              joinEntered.countDown();
              if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("Fixture admission release timed out");
              return true;
            });
    org.mockito.Mockito.doAnswer(
            call -> {
              if (!rejectionWins) rejectEntered.countDown();
              Object result = call.callRealMethod();
              if (rejectionWins) {
                rejectEntered.countDown();
                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                  throw new IllegalStateException("Fixture rejection release timed out");
              }
              return result;
            })
        .when(rejectionTransactions)
        .begin(sessionId);
    org.mockito.Mockito.doAnswer(
            call -> {
              closed.incrementAndGet();
              return true;
            })
        .when(matrixRooms)
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
    java.util.function.Function<String, java.util.concurrent.Callable<Integer>> request =
        endpoint ->
            () -> {
              TenantContext.setCurrentTenant(7L);
              try {
                return mvc.perform(post("/users/sessions/{sessionId}/" + endpoint, sessionId))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              } finally {
                TenantContext.clear();
              }
            };
    try {
      var first = executor.submit(request.apply(rejectionWins ? "rejection" : "team-discussion"));
      assertThat(
              (rejectionWins ? rejectEntered : joinEntered)
                  .await(10, java.util.concurrent.TimeUnit.SECONDS))
          .isTrue();
      var second =
          executor.submit(
              () -> {
                secondRequestStarted.countDown();
                return request.apply(rejectionWins ? "team-discussion" : "rejection").call();
              });
      assertThat(secondRequestStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      if (!rejectionWins)
        assertThat(rejectEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      org.awaitility.Awaitility.await()
          .during(java.time.Duration.ofMillis(200))
          .atMost(java.time.Duration.ofSeconds(3))
          .untilAsserted(
              () -> {
                assertThat(second.isDone()).isFalse();
                assertThat(closed.get()).isZero();
              });
      release.countDown();
      assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(rejectionWins ? 204 : 200);
      assertThat(second.get(10, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(rejectionWins ? 403 : 204);
      assertThat(sessions.findById(sessionId).orElseThrow().getStatus())
          .isEqualTo(Session.SessionStatus.REJECTED);
      assertThat(teamParticipants.findByTeamDiscussionId(team.getId()))
          .hasSize(rejectionWins ? 0 : 1);
      assertThat(teamDiscussions.findById(team.getId()).orElseThrow().isReadOnlyApplied()).isTrue();
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      teamParticipants.deleteAll(teamParticipants.findByTeamDiscussionId(team.getId()));
      teamDiscussions.deleteById(team.getId());
    }
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
        assertThat(retained.getEndedAt()).isNotNull();
        if (phase == CallPhase.LATE_START) assertThat(retained.getStartedAt()).isNull();
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

  @Test
  void rolledBackCallBindingNeverJoinsTheTechnicalObserver() {
    String source = sessions.findById(sessionId).orElseThrow().getMatrixRoomId();
    String media = "!rolled-back-media:synthetic.oriso.test";
    String callerMatrixId = "@seeker:synthetic.oriso.test";
    String callId = "rolled-back-call";
    when(matrix.getCallRoomMembers(source))
        .thenReturn(
            java.util.Optional.of(
                java.util.List.of(callerMatrixId, "@counsellor:synthetic.oriso.test")));
    when(matrix.getCallRoomBinding(media, callerMatrixId))
        .thenReturn(
            java.util.Optional.of(java.util.Map.of("call_id", callId, "source_room_id", source)));
    when(matrix.ensureAdminInRoom(media, callerMatrixId)).thenReturn(true);
    var invite =
        java.util.Map.<String, Object>of(
            "sender",
            callerMatrixId,
            "origin_server_ts",
            System.currentTimeMillis(),
            "content",
            java.util.Map.of("call_id", callId, "call_room_id", media, "lifetime", 60000));
    var transaction =
        new org.springframework.transaction.support.TransactionTemplate(
            context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
    try {
      transaction.executeWithoutResult(
          status -> {
            assertThat(callInvites.handle(source, invite)).isTrue();
            status.setRollbackOnly();
          });
      assertThat(callBindings.findByMediaRoomId(media)).isEmpty();
      org.mockito.Mockito.verify(matrix, org.mockito.Mockito.never())
          .ensureAdminInRoom(media, callerMatrixId);
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
  void finalizationCannotRecreateAnEnquiryThatNoLongerExists() {
    var expected = finalizationSnapshot(sessions.findById(sessionId).orElseThrow());
    sessions.deleteById(sessionId);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                sessionService.finalizeEnquiry(
                    expected, "en", "!finalized-enquiry:synthetic.oriso.test"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException.class);
    assertThat(sessions.findById(sessionId)).isEmpty();
    assertThat(feed()).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void finalizationCommitsTheCurrentEnquiryWithTheAssignedOrUnassignedStatus(boolean assigned) {
    var current = sessions.findById(sessionId).orElseThrow();
    current.setStatus(Session.SessionStatus.INITIAL);
    current.setEnquiryMessageDate(null);
    current.setConsultant(assigned ? consultants.findById(actorId).orElseThrow() : null);
    sessions.save(current);
    var expected = finalizationSnapshot(current);

    sessionService.finalizeEnquiry(expected, "en", "!finalized-enquiry:synthetic.oriso.test");

    var stored = sessions.findById(sessionId).orElseThrow();
    assertThat(stored.getStatus())
        .isEqualTo(assigned ? Session.SessionStatus.IN_PROGRESS : Session.SessionStatus.NEW);
    assertThat(stored.getMatrixRoomId()).isEqualTo("!finalized-enquiry:synthetic.oriso.test");
    assertThat(stored.getEnquiryMessageDate()).isNotNull();
    assertThat(stored.getLanguageCode()).isEqualTo(LanguageCode.en);
    assertThat(stored.getUser().getUserId()).isEqualTo(seekerId);
    assertThat(stored.getTenantId()).isEqualTo(7L);
    assertThat(stored.getAgencyId()).isEqualTo(70L);
    if (assigned) assertThat(stored.getConsultant().getId()).isEqualTo(actorId);
    else assertThat(stored.getConsultant()).isNull();
    assertThat(feed()).isEmpty();
  }

  @Test
  void aSecondFinalizationCannotChangeAnAlreadySubmittedEnquiryEvenWithAFreshBinding() {
    var current = sessions.findById(sessionId).orElseThrow();
    current.setStatus(Session.SessionStatus.INITIAL);
    current.setEnquiryMessageDate(null);
    sessions.save(current);
    sessionService.finalizeEnquiry(
        finalizationSnapshot(current), "en", "!finalized-enquiry:synthetic.oriso.test");
    var submitted = sessions.findById(sessionId).orElseThrow();
    var expected = finalizationSnapshot(submitted);
    var submittedAt = submitted.getEnquiryMessageDate();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                sessionService.finalizeEnquiry(
                    expected, "de", "!second-finalization:synthetic.oriso.test"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);

    var stored = sessions.findById(sessionId).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(Session.SessionStatus.NEW);
    assertThat(stored.getMatrixRoomId()).isEqualTo(submitted.getMatrixRoomId());
    assertThat(stored.getEnquiryMessageDate()).isEqualTo(submittedAt);
    assertThat(stored.getLanguageCode()).isEqualTo(LanguageCode.en);
    assertThat(stored.getUser().getUserId()).isEqualTo(seekerId);
    assertThat(stored.getTenantId()).isEqualTo(7L);
    assertThat(stored.getAgencyId()).isEqualTo(70L);
    assertThat(stored.getConsultant()).isNull();
    assertThat(feed()).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(FinalizationBindingMismatch.class)
  void staleFinalizationBindingCannotMutateTheCurrentUnsubmittedEnquiry(
      FinalizationBindingMismatch mismatch) {
    var current = sessions.findById(sessionId).orElseThrow();
    current.setStatus(Session.SessionStatus.INITIAL);
    current.setEnquiryMessageDate(null);
    sessions.save(current);
    var expected = finalizationSnapshot(current);
    switch (mismatch) {
      case TENANT -> expected.setTenantId(8L);
      case AGENCY -> expected.setAgencyId(71L);
      case USER ->
          expected.setUser(
              new User(
                  UUID.randomUUID().toString(),
                  null,
                  "stale-snapshot",
                  "stale@synthetic.oriso.test",
                  false));
      case ROOM -> expected.setMatrixRoomId("!stale-enquiry:synthetic.oriso.test");
    }

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                sessionService.finalizeEnquiry(
                    expected, "en", "!finalized-enquiry:synthetic.oriso.test"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);

    var stored = sessions.findById(sessionId).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(Session.SessionStatus.INITIAL);
    assertThat(stored.getEnquiryMessageDate()).isNull();
    assertThat(stored.getLanguageCode()).isEqualTo(LanguageCode.de);
    assertThat(stored.getMatrixRoomId()).isEqualTo(current.getMatrixRoomId());
    assertThat(stored.getUser().getUserId()).isEqualTo(seekerId);
    assertThat(stored.getTenantId()).isEqualTo(7L);
    assertThat(stored.getAgencyId()).isEqualTo(70L);
    assertThat(stored.getConsultant()).isNull();
    assertThat(feed()).isEmpty();
  }

  private Session finalizationSnapshot(Session current) {
    return Session.builder()
        .registrationType(current.getRegistrationType())
        .status(current.getStatus())
        .postcode(current.getPostcode())
        .id(current.getId())
        .tenantId(current.getTenantId())
        .agencyId(current.getAgencyId())
        .user(current.getUser())
        .matrixRoomId(current.getMatrixRoomId())
        .build();
  }

  private enum FinalizationBindingMismatch {
    TENANT,
    AGENCY,
    USER,
    ROOM
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
                    .class),
            new de.caritas.cob.userservice.api.service.erstantwort.ErstantwortTenantTextResolver(
                org.mockito.Mockito.mock(
                    de.caritas.cob.userservice.api.service.erstantwort
                        .TenantErstantwortContentClient.class)));
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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void teamRoomCreatedRemotelyDuringRejectionCannotAcquireLateOwnershipOrJoin(
      boolean closureAlreadyConfirmed) throws Exception {
    var room = "!unused-late-team-" + UUID.randomUUID() + ":synthetic.oriso.test";
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    org.mockito.Mockito.doReturn(closureAlreadyConfirmed)
        .when(matrixRooms)
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
    when(matrix.loginAsUserAccessToken("@counsellor:synthetic.oriso.test"))
        .thenReturn("synthetic-counsellor-token");
    when(matrix.joinRoom(room, "synthetic-counsellor-token")).thenReturn(true);
    when(matrix.purgeRoomOrConfirmGone(room))
        .thenReturn(
            de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.RoomPurgeOutcome
                .PURGED);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              executor
                  .submit(
                      () -> {
                        TenantContext.setCurrentTenant(7L);
                        try {
                          mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
                              .andExpect(
                                  closureAlreadyConfirmed
                                      ? status().isNoContent()
                                      : status().isServiceUnavailable());
                        } finally {
                          TenantContext.clear();
                        }
                        return null;
                      })
                  .get(10, java.util.concurrent.TimeUnit.SECONDS);
              var body =
                  new de.caritas.cob.userservice.api.adapters.matrix.dto
                      .MatrixCreateRoomResponseDTO();
              body.setRoomId(room);
              return org.springframework.http.ResponseEntity.ok(body);
            })
        .when(matrix)
        .createRoom(anyString(), anyString(), anyString());
    try {
      mvc.perform(post("/users/sessions/{sessionId}/team-discussion", sessionId))
          .andExpect(status().isConflict());
      assertThat(teamDiscussions.findBySessionId(sessionId)).isEmpty();
      var decision = rejections.findById(sessionId).orElseThrow();
      assertThat(decision.getTeamRoomId()).isNull();
      org.mockito.Mockito.verify(matrix).purgeRoomOrConfirmGone(room);
      org.mockito.Mockito.verify(matrix, org.mockito.Mockito.never())
          .inviteUserToRoom(org.mockito.ArgumentMatchers.eq(room), anyString(), anyString());
      org.mockito.Mockito.verify(matrix, org.mockito.Mockito.never())
          .joinRoom(org.mockito.ArgumentMatchers.eq(room), anyString());
      if (!closureAlreadyConfirmed) {
        assertThat(feed()).isEmpty();
        decision.setNextAttemptAt(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
        rejections.saveAndFlush(decision);
        org.mockito.Mockito.doReturn(true)
            .when(matrixRooms)
            .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
        mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
            .andExpect(status().isNoContent());
      }
      assertThat(rejections.findById(sessionId).orElseThrow().getState())
          .isEqualTo(EnquiryRejection.State.CONFIRMED);
      assertThat(feed())
          .singleElement()
          .extracting(EventNotification::getEventType)
          .isEqualTo("request.denied");
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      // RED cleanup is fixture-owned; a failing old writer may have committed the late row.
      teamDiscussions
          .findBySessionId(sessionId)
          .ifPresent(
              team -> {
                teamParticipants.deleteAll(teamParticipants.findByTeamDiscussionId(team.getId()));
                teamDiscussions.delete(team);
              });
    }
  }

  @Autowired private TeamDiscussionParticipantRepository teamParticipants;

  @Test
  void independentPublicAcceptWaitsForTheSameRejectionRowLockThenCannotReopenIt() throws Exception {
    var locked = new java.util.concurrent.CountDownLatch(1);
    var acceptEntered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              var attempt = invocation.callRealMethod();
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isTrue();
              locked.countDown();
              assertThat(release.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
              return attempt;
            })
        .when(rejectionTransactions)
        .begin(sessionId);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              acceptEntered.countDown();
              return invocation.callRealMethod();
            })
        .when(sessionService)
        .getSessionForUpdate(sessionId);
    try {
      var rejection =
          executor.submit(
              () -> {
                TenantContext.setCurrentTenant(7L);
                try {
                  return mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                } finally {
                  TenantContext.clear();
                }
              });
      assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var acceptance =
          executor.submit(
              () -> {
                TenantContext.setCurrentTenant(7L);
                try {
                  return mvc.perform(
                          org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                              "/users/sessions/new/{sessionId}", sessionId))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                } finally {
                  TenantContext.clear();
                }
              });
      assertThat(acceptEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> acceptance.get(200, java.util.concurrent.TimeUnit.MILLISECONDS))
          .isInstanceOf(java.util.concurrent.TimeoutException.class);
      assertThat(feed()).isEmpty();
      release.countDown();
      assertThat(rejection.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(204);
      assertThat(acceptance.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(409);
      var current = sessions.findById(sessionId).orElseThrow();
      assertThat(current.getStatus()).isEqualTo(Session.SessionStatus.REJECTED);
      assertThat(current.getConsultant()).isNull();
      assertThat(feed())
          .singleElement()
          .extracting(EventNotification::getEventType)
          .isEqualTo("request.denied");
      org.mockito.Mockito.verify(matrix, org.mockito.Mockito.never())
          .inviteUserToRoom(anyString(), anyString(), anyString());
      org.mockito.Mockito.verify(matrix, org.mockito.Mockito.never())
          .joinRoom(anyString(), anyString());
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void expiredFirstFinisherCannotOverwriteOrPrematurelyConfirmTheSecondClaimWinner(
      boolean firstReturnsAfterWinner) throws Exception {
    var firstEntered = new java.util.concurrent.CountDownLatch(1);
    var secondEntered = new java.util.concurrent.CountDownLatch(1);
    var releaseFirst = new java.util.concurrent.CountDownLatch(1);
    var releaseSecond = new java.util.concurrent.CountDownLatch(1);
    var sequence = new java.util.concurrent.atomic.AtomicInteger();
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              if (sequence.incrementAndGet() == 1) {
                firstEntered.countDown();
                assertThat(releaseFirst.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                return !firstReturnsAfterWinner;
              }
              secondEntered.countDown();
              assertThat(releaseSecond.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
              return true;
            })
        .when(matrixRooms)
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
    java.util.concurrent.Callable<Integer> request =
        () -> {
          TenantContext.setCurrentTenant(7L);
          try {
            return mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
                .andReturn()
                .getResponse()
                .getStatus();
          } finally {
            TenantContext.clear();
          }
        };
    try {
      var first = executor.submit(request);
      assertThat(firstEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var original = rejections.findById(sessionId).orElseThrow();
      var originalToken = original.getClaimToken();
      original.setClaimUntil(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
      original.setNextAttemptAt(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
      rejections.saveAndFlush(original);
      var second = executor.submit(request);
      assertThat(secondEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var winner = rejections.findById(sessionId).orElseThrow();
      assertThat(winner.getClaimToken()).isNotEqualTo(originalToken);
      assertThat(winner.getAttemptCount()).isEqualTo(2);
      assertThat(winner.getState()).isEqualTo(EnquiryRejection.State.PENDING);
      assertThat(feed()).isEmpty();
      org.mockito.Mockito.verifyNoInteractions(feedSignal);
      if (firstReturnsAfterWinner) {
        releaseSecond.countDown();
        assertThat(second.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(204);
        releaseFirst.countDown();
        assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(204);
      } else {
        releaseFirst.countDown();
        assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(503);
        var stillPending = rejections.findById(sessionId).orElseThrow();
        assertThat(stillPending.getState()).isEqualTo(EnquiryRejection.State.PENDING);
        assertThat(stillPending.getClaimToken()).isEqualTo(winner.getClaimToken());
        assertThat(stillPending.getFailureStage()).isNull();
        assertThat(feed()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(feedSignal);
        releaseSecond.countDown();
        assertThat(second.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(204);
      }
      var confirmed = rejections.findById(sessionId).orElseThrow();
      assertThat(confirmed.getState()).isEqualTo(EnquiryRejection.State.CONFIRMED);
      assertThat(confirmed.getActorId()).isEqualTo(original.getActorId());
      assertThat(confirmed.getRejectedAt()).isEqualTo(original.getRejectedAt());
      assertThat(confirmed.getGeneration()).isEqualTo(original.getGeneration());
      assertThat(confirmed.getClaimToken()).isNull();
      assertThat(confirmed.getFailureStage()).isNull();
      assertThat(feed())
          .singleElement()
          .extracting(EventNotification::getEventType)
          .isEqualTo("request.denied");
      org.mockito.Mockito.verify(feedSignal, org.mockito.Mockito.times(1))
          .signalFeedUpdated(seekerId);
      org.mockito.Mockito.verify(matrixRooms, org.mockito.Mockito.times(2))
          .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
    } finally {
      releaseFirst.countDown();
      releaseSecond.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
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
  @org.junit.jupiter.api.extension.ExtendWith(
      org.springframework.boot.test.system.OutputCaptureExtension.class)
  void unexpectedStorageFailureIsDiagnosedWithoutPretendingTheBindingChanged(
      org.springframework.boot.test.system.CapturedOutput output) throws Exception {
    org.mockito.Mockito.doReturn(false)
        .when(matrixRooms)
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isServiceUnavailable());
    var pending = rejections.findById(sessionId).orElseThrow();
    pending.setNextAttemptAt(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
    rejections.saveAndFlush(pending);
    var scheduler =
        new de.caritas.cob.userservice.api.service.enquiry.EnquiryRejectionRepairScheduler(
            rejections,
            rejectionService,
            context.getBean(
                de.caritas.cob.userservice.api.service.enquiry.EnquiryRejectionTransactions.class),
            context.getBean(java.time.Clock.class));
    jdbc.execute(
        "ALTER TABLE enquiry_rejection ADD CONSTRAINT n12_attempt_storage_fault CHECK (attempt_count < 2)");
    try {
      scheduler.retryPending();
      var retained = rejections.findById(sessionId).orElseThrow();
      assertThat(retained.getState()).isEqualTo(EnquiryRejection.State.PENDING);
      assertThat(retained.getFailureStage()).isEqualTo(pending.getFailureStage());
      assertThat(retained.getGeneration()).isEqualTo(pending.getGeneration());
      assertThat(feed()).isEmpty();
      assertThat(output)
          .contains("Enquiry rejection repair failed for session " + sessionId)
          .contains("DataIntegrityViolationException");
    } finally {
      jdbc.execute("ALTER TABLE enquiry_rejection DROP CONSTRAINT n12_attempt_storage_fault");
    }
    org.mockito.Mockito.doReturn(true)
        .when(matrixRooms)
        .closeRoomForMessagesVerified(anyString(), anyString(), anyString());
    scheduler.retryPending();
    assertThat(rejections.findById(sessionId).orElseThrow().getState())
        .isEqualTo(EnquiryRejection.State.CONFIRMED);
    assertThat(feed()).hasSize(1);
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
    when(caller.isConsultant()).thenReturn(true);
    when(caller.getUserId()).thenReturn(actorId);
    when(caller.getRoles()).thenReturn(Set.of("consultant"));
    mvc.perform(post("/users/sessions/{sessionId}/rejection", sessionId))
        .andExpect(status().isNoContent());
  }
}
