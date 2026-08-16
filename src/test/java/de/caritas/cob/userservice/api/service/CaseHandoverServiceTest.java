package de.caritas.cob.userservice.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.CaseHandoverReasonPolicy;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CaseHandoverReasonPolicyRepository;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.CaseHandoverService.CaseHandoverReason;
import de.caritas.cob.userservice.api.service.CaseHandoverService.CaseHandoverStatus;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CaseHandoverServiceTest {

  @InjectMocks private CaseHandoverService caseHandoverService;

  @Mock private CaseHandoverRequestRepository caseHandoverRequestRepository;
  @Mock private CaseHandoverReasonPolicyRepository caseHandoverReasonPolicyRepository;
  @Mock private CaseHandoverPolicyCacheService caseHandoverPolicyCacheService;
  @Mock private SessionRepository sessionRepository;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private UserAccountService userAccountService;
  @Mock private EventNotificationService eventNotificationService;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private MatrixSessionSystemMessageService matrixSessionSystemMessageService;
  @Mock private ScheduledTaskClaimService scheduledTaskClaimService;
  @Spy private Clock clock = Clock.fixed(Instant.parse("2026-08-16T10:00:00Z"), ZoneOffset.UTC);

  private Consultant requester;
  private Consultant previous;
  private User asker;
  private Session session;

  @BeforeEach
  void setUp() {
    requester = consultant("requester", "Requesting Counsellor");
    previous = consultant("previous", "Previous Counsellor");

    ConsultantAgency requesterAgency = new ConsultantAgency();
    requesterAgency.setAgencyId(10L);
    requesterAgency.setConsultant(requester);
    requester.setConsultantAgencies(Set.of(requesterAgency));

    asker = new User();
    asker.setUserId("asker");
    asker.setUsername("asker");

    session = new Session();
    session.setId(123L);
    session.setAgencyId(10L);
    session.setConsultant(previous);
    session.setUser(asker);
    session.setStatus(SessionStatus.IN_PROGRESS);
    session.setRegistrationType(Session.RegistrationType.REGISTERED);
    session.setMatrixRoomId(null);
    session.setTenantId(7L);
    session.setPostcode("12345");
    session.setLanguageCode(LanguageCode.de);
    session.setCreateDate(LocalDateTime.now());
    session.setUpdateDate(LocalDateTime.now());

    when(userAccountService.retrieveValidatedConsultant()).thenReturn(requester);
    when(userAccountService.retrieveValidatedUser()).thenReturn(asker);
    when(sessionRepository.findById(123L)).thenReturn(Optional.of(session));
    when(caseHandoverReasonPolicyRepository.findByEnabledTrueOrderByDisplayOrderAscCodeAsc())
        .thenReturn(List.of());
    when(caseHandoverReasonPolicyRepository.findAllByOrderByDisplayOrderAscCodeAsc())
        .thenReturn(List.of());
    when(caseHandoverPolicyCacheService.getEffective(any())).thenReturn(null);
    when(caseHandoverRequestRepository.findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
            123L, "requester"))
        .thenReturn(List.of());
    when(caseHandoverRequestRepository.findBySessionIdAndStatusOrderByCreatedAtDesc(
            123L, CaseHandoverRequest.Status.GRANTED))
        .thenReturn(List.of());
    when(caseHandoverRequestRepository.save(any(CaseHandoverRequest.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(scheduledTaskClaimService.tryClaim(anyString(), any())).thenReturn(true);
  }

  @Test
  void listReasons_keepsResolvedPoliciesIsolatedByTenant() {
    when(caseHandoverPolicyCacheService.getEffective(7L))
        .thenReturn(tenantPolicies("Rat ben\u00f6tigt", 15));
    when(caseHandoverPolicyCacheService.getEffective(8L))
        .thenReturn(tenantPolicies("Advice needed", 345));

    var tenantSeven = caseHandoverService.listReasons(7L);
    var tenantEight = caseHandoverService.listReasons(8L);

    assertEquals("Rat ben\u00f6tigt", tenantSeven.get(0).getLabel());
    assertEquals(15, tenantSeven.get(0).getMaxAccessDurationMinutes());
    assertEquals("Advice needed", tenantEight.get(0).getLabel());
    assertEquals(345, tenantEight.get(0).getMaxAccessDurationMinutes());
  }

  @Test
  void listReasons_doesNotHideInvalidTenantPolicyBehindTheLegacyFallback() {
    when(caseHandoverPolicyCacheService.getEffective(7L))
        .thenReturn(tenantPolicies("Rat benötigt", 10));
    when(caseHandoverReasonPolicyRepository.findByEnabledTrueOrderByDisplayOrderAscCodeAsc())
        .thenReturn(
            List.of(reasonPolicy("COUNSELLOR_ASKED_FOR_ADVICE", "Legacy", true, true, true, 10)));

    assertThrows(BadRequestException.class, () -> caseHandoverService.listReasons(7L));
  }

  @Test
  void requestAccess_usesTenantResolvedAdviceDurationAndTemplate() {
    when(caseHandoverPolicyCacheService.getEffective(7L))
        .thenReturn(tenantPolicies("Rat ben\u00f6tigt", 15));

    caseHandoverService.requestAccess(123L, "COUNSELLOR_ASKED_FOR_ADVICE", "Zweitmeinung");

    ArgumentCaptor<CaseHandoverRequest> request =
        ArgumentCaptor.forClass(CaseHandoverRequest.class);
    verify(caseHandoverRequestRepository).save(request.capture());
    assertEquals("Rat ben\u00f6tigt", request.getValue().getReasonLabel());
    assertEquals(15, request.getValue().getMaxAccessDurationMinutes());
    assertEquals(CaseHandoverRequest.Status.PENDING_CLIENT_CONSENT, request.getValue().getStatus());
    assertNull(request.getValue().getExpiresAt());
  }

  @Test
  void requestAccess_grantsAndActivatesCounsellor_WhenPolicyDoesNotRequireClientConsent() {
    CaseHandoverStatus status =
        caseHandoverService.requestAccess(123L, "OTHER_EMERGENCY", "Colleague is unavailable.");

    assertEquals("GRANTED", status.getStatus());
    assertTrue(status.isCanViewContent());
    assertFalse(status.isClientConsentRequired());
    assertEquals(requester, session.getConsultant());
    verify(sessionRepository).save(session);
    verify(eventNotificationService, atLeastOnce())
        .createEvent(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  /**
   * #1010 task 1a: the handover explanation is free text a counsellor writes and can reference case
   * content. It used to be formatted into {@code event_notification.text}, a table with no
   * retention that outlives the case, which made it the one place counselling content sat in
   * plaintext. The client reads it from the handover request instead.
   */
  @Test
  void requestAccess_neverCopiesTheExplanationIntoAStoredNotification() {
    caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Client disclosed self-harm.");

    ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
    verify(eventNotificationService, atLeastOnce())
        .createEvent(any(), any(), any(), any(), text.capture(), any(), any(), any(), any());

    assertTrue(
        text.getAllValues().stream()
            .noneMatch(value -> value != null && value.contains("Client disclosed self-harm")),
        "stored notification text must not carry the counsellor's explanation");
    assertTrue(
        text.getAllValues().stream()
            .noneMatch(value -> value != null && value.contains("Explanation")),
        "the explanation label must be gone too, not just this sample's wording");
  }

  /** The reason stays — it is a configured label, not free text — and moves into params. */
  @Test
  void requestAccess_carriesRequesterAndReasonAsParams() {
    caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Illness cover.");

    verify(eventNotificationService, atLeastOnce())
        .buildCaseHandoverParams(any(), anyString(), eq("COUNSELLOR_IS_ILL"), any(), any());
  }

  @Test
  void requestAccess_invitesRequesterToExistingMatrixRoom_WhenGranted() throws Exception {
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    previous.setMatrixUserId("@previous:matrix");
    when(matrixSynapseService.loginAsUserAccessToken("@previous:matrix"))
        .thenReturn("previous-token");
    when(matrixSynapseService.loginAsUserAccessToken("@requester:matrix"))
        .thenReturn("requester-token");
    when(matrixSynapseService.joinRoom("!room:matrix", "requester-token")).thenReturn(true);

    caseHandoverService.requestAccess(123L, "OTHER_EMERGENCY", "Colleague is unavailable.");

    verify(matrixSynapseService)
        .inviteUserToRoom("!room:matrix", "@requester:matrix", "previous-token");
    verify(matrixSynapseService).joinRoom("!room:matrix", "requester-token");
    // ADR-002: a takeover re-hides the original counsellor but keeps their membership, so they
    // can reclaim the case. Removing them here would make the history unrecoverable under Megolm.
    verify(matrixSynapseService, never()).leaveRoom(anyString(), anyString());
  }

  /**
   * Reproduced on Pre-Dev 2026-07-30: since #905 the requester is already a member of the enquiry
   * room, and Synapse rejects the invite with 403 "<user> is already in the room". Before this test
   * the rejection was turned into a 500 and the handover failed outright.
   */
  @Test
  void requestAccess_grantsAccess_WhenRequesterIsAlreadyAMemberAndTheInviteIsRejected()
      throws Exception {
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    previous.setMatrixUserId("@previous:matrix");
    when(matrixSynapseService.loginAsUserAccessToken("@previous:matrix"))
        .thenReturn("previous-token");
    when(matrixSynapseService.loginAsUserAccessToken("@requester:matrix"))
        .thenReturn("requester-token");
    when(matrixSynapseService.inviteUserToRoom(
            "!room:matrix", "@requester:matrix", "previous-token"))
        .thenThrow(new MatrixInviteUserException("@requester:matrix is already in the room."));
    when(matrixSynapseService.joinRoom("!room:matrix", "requester-token")).thenReturn(true);

    CaseHandoverStatus status =
        caseHandoverService.requestAccess(123L, "OTHER_EMERGENCY", "Colleague is unavailable.");

    assertEquals("GRANTED", status.getStatus());
    assertEquals(requester, session.getConsultant());
    verify(matrixSynapseService).joinRoom("!room:matrix", "requester-token");
    verify(sessionRepository).save(session);
  }

  @Test
  void requestAccess_doesNotActivateRequester_WhenRequesterCannotJoinMatrixRoom() throws Exception {
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    previous.setMatrixUserId("@previous:matrix");
    when(matrixSynapseService.loginAsUserAccessToken("@previous:matrix"))
        .thenReturn("previous-token");
    when(matrixSynapseService.loginAsUserAccessToken("@requester:matrix"))
        .thenReturn("requester-token");
    when(matrixSynapseService.joinRoom("!room:matrix", "requester-token")).thenReturn(false);

    assertThrows(
        InternalServerErrorException.class,
        () ->
            caseHandoverService.requestAccess(
                123L, "OTHER_EMERGENCY", "Colleague is unavailable."));

    assertEquals(previous, session.getConsultant());
    verify(sessionRepository, never()).save(session);
  }

  @Test
  void requestAccess_postsCaseHandoverSystemMessage_WhenGranted() throws Exception {
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    previous.setMatrixUserId("@previous:matrix");
    when(matrixSynapseService.loginAsUserAccessToken(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn("token");
    when(matrixSynapseService.joinRoom("!room:matrix", "token")).thenReturn(true);

    caseHandoverService.requestAccess(123L, "OTHER_EMERGENCY", "Colleague is unavailable.");

    verify(matrixSessionSystemMessageService)
        .postCaseHandoverGrantedMessage(
            org.mockito.ArgumentMatchers.eq(session),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("Other emergency"),
            org.mockito.ArgumentMatchers.eq("Colleague is unavailable."),
            org.mockito.ArgumentMatchers.contains("deinen Fall übernommen"));
  }

  @Test
  void requestAccess_keepsContentLocked_WhenPolicyRequiresClientConsent() {
    CaseHandoverStatus status =
        caseHandoverService.requestAccess(
            123L, "COUNSELLOR_ASKED_FOR_ADVICE", "Need a second opinion.");

    assertEquals("PENDING_CLIENT_CONSENT", status.getStatus());
    assertFalse(status.isCanViewContent());
    assertTrue(status.isClientConsentRequired());
    assertEquals(previous, session.getConsultant());
    verify(sessionRepository, never()).save(session);
  }

  @Test
  void resolveAdviceConsent_grantsThreeHourReadOnlyCoAccessWithoutChangingOwner() {
    CaseHandoverRequest request = pendingConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));

    CaseHandoverStatus status = caseHandoverService.resolveClientConsent(123L, 88L, true);

    assertEquals("CO_ACCESS", status.getAccessType());
    assertEquals(LocalDateTime.of(2026, 8, 16, 13, 0), status.getExpiresAt());
    assertEquals(previous, session.getConsultant());
    verify(sessionRepository, never()).save(session);
  }

  @Test
  void requestTakeover_hasNoExpiryAndChangesOwner() {
    ArgumentCaptor<CaseHandoverRequest> savedRequest =
        ArgumentCaptor.forClass(CaseHandoverRequest.class);

    CaseHandoverStatus status =
        caseHandoverService.requestAccess(123L, "OTHER_EMERGENCY", "Urgent cover.");

    assertEquals("TAKEOVER", status.getAccessType());
    assertNull(status.getExpiresAt());
    assertEquals(requester, session.getConsultant());
    verify(caseHandoverRequestRepository).save(savedRequest.capture());
    assertNull(savedRequest.getValue().getMaxAccessDurationMinutes());
  }

  @Test
  void requestAccess_requiresActiveConsentWhenTeamAccessIsDisabled() {
    session.setSupervisionOptedOut(true);

    CaseHandoverStatus status =
        caseHandoverService.requestAccess(123L, "OTHER_EMERGENCY", "Urgent cover.");

    assertEquals("PENDING_CLIENT_CONSENT", status.getStatus());
    assertTrue(status.isClientConsentRequired());
    assertEquals(previous, session.getConsultant());
    ArgumentCaptor<CaseHandoverRequest> savedRequest =
        ArgumentCaptor.forClass(CaseHandoverRequest.class);
    verify(caseHandoverRequestRepository).save(savedRequest.capture());
    assertEquals("PENDING_CLIENT_CONSENT", savedRequest.getValue().getAuditOutcome());
  }

  @Test
  void getStatus_closesCoAccessExactlyAtExpiryEvenBeforeSweepRuns() {
    CaseHandoverRequest request = grantedAdviceRequest();
    request.setExpiresAt(LocalDateTime.of(2026, 8, 16, 10, 0));
    when(caseHandoverRequestRepository.findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
            123L, "requester"))
        .thenReturn(List.of(request));

    CaseHandoverStatus status = caseHandoverService.getStatus(123L);

    assertEquals("EXPIRED", status.getStatus());
    assertFalse(status.isCanViewContent());
  }

  @Test
  void expireCoAccess_persistsAuditStateUsingInjectedClock() {
    CaseHandoverRequest request = grantedAdviceRequest();
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    previous.setMatrixUserId("@previous:matrix");
    when(matrixSynapseService.getRoomMembers("!room:matrix"))
        .thenReturn(Optional.of(List.of("@requester:matrix")));
    when(matrixSynapseService.loginAsUserAccessToken("@previous:matrix"))
        .thenReturn("previous-token");
    when(matrixSynapseService.removeUserFromRoom(
            "!room:matrix", "@requester:matrix", "previous-token"))
        .thenReturn(true);
    when(caseHandoverRequestRepository.findByStatusAndAccessTypeAndExpiresAtLessThanEqual(
            CaseHandoverRequest.Status.GRANTED,
            CaseHandoverRequest.AccessType.CO_ACCESS,
            LocalDateTime.of(2026, 8, 16, 10, 0)))
        .thenReturn(List.of(request));

    assertEquals(1, caseHandoverService.expireCoAccess());

    assertEquals(CaseHandoverRequest.Status.EXPIRED, request.getStatus());
    assertEquals("ACCESS_EXPIRED", request.getAuditOutcome());
    verify(matrixSynapseService)
        .removeUserFromRoom("!room:matrix", "@requester:matrix", "previous-token");
    verify(caseHandoverRequestRepository).saveAll(List.of(request));
  }

  @Test
  void expireCoAccess_keepsTheLeaseGrantedWhenMatrixRemovalCannotBeConfirmed() {
    CaseHandoverRequest request = grantedAdviceRequest();
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    previous.setMatrixUserId("@previous:matrix");
    when(matrixSynapseService.getRoomMembers("!room:matrix"))
        .thenReturn(Optional.of(List.of("@requester:matrix")));
    when(matrixSynapseService.loginAsUserAccessToken("@previous:matrix"))
        .thenReturn("previous-token");
    when(caseHandoverRequestRepository.findByStatusAndAccessTypeAndExpiresAtLessThanEqual(
            CaseHandoverRequest.Status.GRANTED,
            CaseHandoverRequest.AccessType.CO_ACCESS,
            LocalDateTime.of(2026, 8, 16, 10, 0)))
        .thenReturn(List.of(request));

    assertEquals(0, caseHandoverService.expireCoAccess());

    assertEquals(CaseHandoverRequest.Status.GRANTED, request.getStatus());
    verify(caseHandoverRequestRepository).saveAll(List.of());
  }

  @Test
  void expireCoAccess_keepsProcessingWhenMatrixReconciliationFails() {
    CaseHandoverRequest failingRequest = grantedAdviceRequest();
    failingRequest.setId(100L);
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    when(matrixSynapseService.getRoomMembers("!room:matrix"))
        .thenThrow(new IllegalStateException("Matrix unavailable"));
    when(caseHandoverRequestRepository.findByStatusAndAccessTypeAndExpiresAtLessThanEqual(
            CaseHandoverRequest.Status.GRANTED,
            CaseHandoverRequest.AccessType.CO_ACCESS,
            LocalDateTime.of(2026, 8, 16, 10, 0)))
        .thenReturn(List.of(failingRequest));

    assertEquals(0, caseHandoverService.expireCoAccess());

    assertEquals(CaseHandoverRequest.Status.GRANTED, failingRequest.getStatus());
    verify(caseHandoverRequestRepository).saveAll(List.of());
  }

  @Test
  void expirySchedulerEntrypoint_isVoidForSharedSchedulerAdvice() throws Exception {
    var method = CaseHandoverService.class.getMethod("expireCoAccessSchedule");

    assertEquals(void.class, method.getReturnType());
    assertTrue(
        method.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class));
  }

  @Test
  void expiryScheduler_usesSharedLeaseAndTechnicalTenantContext() {
    when(caseHandoverRequestRepository.findByStatusAndAccessTypeAndExpiresAtLessThanEqual(
            any(), any(), any()))
        .thenAnswer(
            invocation -> {
              assertEquals(TenantContext.TECHNICAL_TENANT_ID, TenantContext.getCurrentTenant());
              return List.of();
            });

    caseHandoverService.expireCoAccessSchedule();

    verify(scheduledTaskClaimService).tryClaim(eq("case-handover-co-access-expiry"), any());
    assertNull(TenantContext.getCurrentTenant());
  }

  @Test
  void revokeActiveCoAccess_doesNotSelectCompletedTakeovers() {
    CaseHandoverRequest request = grantedAdviceRequest();
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    previous.setMatrixUserId("@previous:matrix");
    when(matrixSynapseService.getRoomMembers("!room:matrix"))
        .thenReturn(Optional.of(List.of("@requester:matrix")));
    when(matrixSynapseService.loginAsUserAccessToken("@previous:matrix"))
        .thenReturn("previous-token");
    when(matrixSynapseService.removeUserFromRoom(
            "!room:matrix", "@requester:matrix", "previous-token"))
        .thenReturn(true);
    when(caseHandoverRequestRepository.findBySessionIdAndStatusAndAccessType(
            123L, CaseHandoverRequest.Status.GRANTED, CaseHandoverRequest.AccessType.CO_ACCESS))
        .thenReturn(List.of(request));

    assertEquals(1, caseHandoverService.revokeActiveCoAccessForSession(123L));

    assertEquals(CaseHandoverRequest.Status.REVOKED, request.getStatus());
    assertEquals("ACCESS_REVOKED", request.getAuditOutcome());
    verify(matrixSynapseService)
        .removeUserFromRoom("!room:matrix", "@requester:matrix", "previous-token");
  }

  @Test
  void requestAccess_deniesAndKeepsContentLocked_WhenPolicyDoesNotAllowReason() {
    when(caseHandoverReasonPolicyRepository.findByEnabledTrueOrderByDisplayOrderAscCodeAsc())
        .thenReturn(
            List.of(reasonPolicy("OTHER_EMERGENCY", "Other emergency", false, false, true, 30)));

    CaseHandoverStatus status =
        caseHandoverService.requestAccess(123L, "OTHER_EMERGENCY", "Needs cover.");

    assertEquals("DENIED", status.getStatus());
    assertFalse(status.isCanViewContent());
    assertEquals("ACCESS_DENIED", status.getAuditOutcome());
    assertEquals(previous, session.getConsultant());
    verify(sessionRepository, never()).save(session);
  }

  @Test
  void requestAccess_deniesAndKeepsContentLocked_WhenCaseAlreadyGrantedToAnotherCounsellor() {
    Consultant other = consultant("other", "Other Counsellor");
    when(caseHandoverRequestRepository.findBySessionIdAndStatusOrderByCreatedAtDesc(
            123L, CaseHandoverRequest.Status.GRANTED))
        .thenReturn(List.of(grantedRequest(other)));

    CaseHandoverStatus status =
        caseHandoverService.requestAccess(123L, "OTHER_EMERGENCY", "Needs cover.");

    assertEquals("DENIED", status.getStatus());
    assertFalse(status.isCanViewContent());
    assertEquals("ALREADY_ANSWERED", status.getAuditOutcome());
    assertEquals(previous, session.getConsultant());
    verify(sessionRepository, never()).save(session);
  }

  @Test
  void getStatus_returnsNotRequestedLockedState_WhenNoRequestExists() {
    CaseHandoverStatus status = caseHandoverService.getStatus(123L);

    assertEquals("NOT_REQUESTED", status.getStatus());
    assertFalse(status.isCanViewContent());
  }

  @Test
  void searchCandidates_returnsMetadataOnlySameAgencyMatches() {
    when(sessionRepository
            .findByAgencyIdInAndConsultantNotAndStatusInAndTeamSessionFalseOrderByUpdateDateDesc(
                List.of(10L), requester, List.of(SessionStatus.IN_PROGRESS, SessionStatus.DONE)))
        .thenReturn(List.of(session));

    var response = caseHandoverService.searchCandidates("asker", 0, 15, false);

    assertEquals(1, response.getTotal());
    assertEquals(1, response.getCount());
    var candidate = response.getSessions().get(0);
    assertEquals(123L, candidate.getSession().getId());
    assertEquals("asker", candidate.getUser().getUsername());
    assertNull(candidate.getUser().getSessionData());
    assertEquals("previous", candidate.getConsultant().getId());
  }

  @Test
  void searchCandidates_matchesInternalDisplayNameOnlyQuery() {
    // The candidate list renders the internal name with fallback (#996), so a query matching
    // ONLY the internal name must not filter the session out before rendering; the public
    // display name stays a valid search term as well.
    previous.setDisplayName("Anna B.");
    previous.setInternalDisplayName("Standort Nord Team 7");
    when(sessionRepository
            .findByAgencyIdInAndConsultantNotAndStatusInAndTeamSessionFalseOrderByUpdateDateDesc(
                List.of(10L), requester, List.of(SessionStatus.IN_PROGRESS, SessionStatus.DONE)))
        .thenReturn(List.of(session));

    var internalNameResponse = caseHandoverService.searchCandidates("standort nord", 0, 15, false);
    var publicNameResponse = caseHandoverService.searchCandidates("anna b", 0, 15, false);

    assertEquals(1, internalNameResponse.getTotal());
    assertEquals(
        "Standort Nord Team 7",
        internalNameResponse.getSessions().get(0).getConsultant().getDisplayName());
    assertEquals(1, publicNameResponse.getTotal());
  }

  @Test
  void searchCandidates_matchesDecodedUsernames() {
    UsernameTranscoder usernameTranscoder = new UsernameTranscoder();
    asker.setUsername(usernameTranscoder.encodeUsername("codexasker1782348153159"));
    previous.setUsername(usernameTranscoder.encodeUsername("codexcounselor20260625023940"));
    previous.setDisplayName(usernameTranscoder.encodeUsername("Codex Counselor"));
    when(sessionRepository
            .findByAgencyIdInAndConsultantNotAndStatusInAndTeamSessionFalseOrderByUpdateDateDesc(
                List.of(10L), requester, List.of(SessionStatus.IN_PROGRESS, SessionStatus.DONE)))
        .thenReturn(List.of(session));

    var askerResponse = caseHandoverService.searchCandidates("codexasker", 0, 15, false);
    var consultantResponse = caseHandoverService.searchCandidates("codexcounselor", 0, 15, false);

    assertEquals(1, askerResponse.getTotal());
    assertEquals(1, consultantResponse.getTotal());
    var candidate = askerResponse.getSessions().get(0);
    assertEquals("codexasker1782348153159", candidate.getUser().getUsername());
    assertEquals("codexcounselor20260625023940", candidate.getConsultant().getUsername());
    assertEquals("Codex Counselor", candidate.getConsultant().getDisplayName());
  }

  @Test
  void requestAccess_persistsReasonExplanationAndAuditOutcome() {
    ArgumentCaptor<CaseHandoverRequest> captor = ArgumentCaptor.forClass(CaseHandoverRequest.class);

    caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Illness cover.");

    verify(caseHandoverRequestRepository).save(captor.capture());
    CaseHandoverRequest saved = captor.getValue();
    assertEquals("COUNSELLOR_IS_ILL", saved.getReasonCode());
    assertEquals("Absence", saved.getReasonLabel());
    assertEquals("Illness cover.", saved.getExplanation());
    assertEquals("ACCESS_GRANTED", saved.getAuditOutcome());
    assertEquals(previous, saved.getPreviousConsultant());
  }

  @Test
  void resolveClientConsent_grantsCoAccessWithoutReplacingOwner_WhenClientApprovesAdvice() {
    CaseHandoverRequest request = pendingConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));

    CaseHandoverStatus status = caseHandoverService.resolveClientConsent(123L, 88L, true);

    assertEquals("GRANTED", status.getStatus());
    assertTrue(status.isCanViewContent());
    assertEquals(previous, session.getConsultant());
    assertEquals(CaseHandoverRequest.Status.GRANTED, request.getStatus());
    assertEquals("ACCESS_GRANTED", request.getAuditOutcome());
    verify(sessionRepository, never()).save(session);
  }

  @Test
  void resolveClientConsent_keepsTheDurationCapturedWhenTheRequestWasCreated() {
    when(caseHandoverPolicyCacheService.getEffective(7L))
        .thenReturn(tenantPolicies("Rat ben\u00f6tigt", 15));
    CaseHandoverRequest request = pendingConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));

    caseHandoverService.resolveClientConsent(123L, 88L, true);

    assertEquals(180, request.getMaxAccessDurationMinutes());
    assertEquals(
        LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).plusMinutes(180),
        request.getExpiresAt());
  }

  @Test
  void resolveClientConsent_canApproveACapturedRequestAfterTheReasonWasDisabled() {
    var disabledPolicies = tenantPolicies("Rat benötigt", 15);
    disabledPolicies.getReasons().get("COUNSELLOR_ASKED_FOR_ADVICE").getEnabled().setValue(false);
    when(caseHandoverPolicyCacheService.getEffective(7L)).thenReturn(disabledPolicies);
    CaseHandoverRequest request = pendingConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));

    CaseHandoverStatus status = caseHandoverService.resolveClientConsent(123L, 88L, true);

    assertEquals("GRANTED", status.getStatus());
    assertEquals(180, request.getMaxAccessDurationMinutes());
  }

  @Test
  void formatDuration_usesTheUkrainianGenitivePluralForFiveHours() {
    String duration =
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            caseHandoverService, "formatDuration", 300, "uk");

    assertEquals("5 годин", duration);
  }

  @ParameterizedTest
  @CsvSource({
    "de,3 Stunden",
    "en,3 hours",
    "fr,3 heures",
    "ru,3 часа",
    "tr,3 saat",
    "uk,3 години",
    "ti,3 ሰዓታት"
  })
  void formatDuration_localizesEveryAdminTemplateLanguage(String language, String expected) {
    String duration =
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            caseHandoverService, "formatDuration", 180, language);

    assertEquals(expected, duration);
  }

  @Test
  void resolveClientConsent_describesTemporaryCoAccessAndKeepsExistingOwner() {
    CaseHandoverRequest request = pendingConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));
    ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);

    caseHandoverService.resolveClientConsent(123L, 88L, true);

    verify(matrixSessionSystemMessageService)
        .postCaseHandoverGrantedMessage(
            org.mockito.ArgumentMatchers.eq(session),
            org.mockito.ArgumentMatchers.eq("Requesting Counsellor"),
            org.mockito.ArgumentMatchers.eq("Advice needed"),
            org.mockito.ArgumentMatchers.eq("Need a second opinion."),
            description.capture());
    assertTrue(description.getValue().contains("zeitlich begrenzt mitlesen"));
    assertTrue(description.getValue().contains("3 Stunden"));
    assertTrue(description.getValue().contains("bleibt für dich zuständig"));
    assertFalse(description.getValue().contains("Fall übernommen"));
  }

  @Test
  void resolveClientConsent_keepsContentLocked_WhenClientDeclines() {
    CaseHandoverRequest request = pendingConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));

    CaseHandoverStatus status = caseHandoverService.resolveClientConsent(123L, 88L, false);

    assertEquals("CLIENT_CONSENT_DECLINED", status.getStatus());
    assertFalse(status.isCanViewContent());
    assertEquals(previous, session.getConsultant());
    assertEquals(CaseHandoverRequest.Status.CLIENT_CONSENT_DECLINED, request.getStatus());
    assertEquals("CLIENT_CONSENT_DECLINED", request.getAuditOutcome());
    verify(sessionRepository, never()).save(session);
  }

  @Test
  void resolveClientConsent_deniesAndDoesNotOverwrite_WhenCaseWasAlreadyTakenOver() {
    Consultant other = consultant("other", "Other Counsellor");
    session.setConsultant(other);
    CaseHandoverRequest request = pendingConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));

    CaseHandoverStatus status = caseHandoverService.resolveClientConsent(123L, 88L, true);

    assertEquals("DENIED", status.getStatus());
    assertFalse(status.isCanViewContent());
    assertEquals("ALREADY_ANSWERED", status.getAuditOutcome());
    assertEquals(other, session.getConsultant());
    assertEquals(CaseHandoverRequest.Status.DENIED, request.getStatus());
    assertEquals("ALREADY_ANSWERED", request.getAuditOutcome());
    verify(sessionRepository, never()).save(session);
  }

  private Consultant consultant(String id, String displayName) {
    Consultant consultant = new Consultant();
    consultant.setId(id);
    consultant.setUsername(id);
    consultant.setFirstName(id);
    consultant.setLastName("User");
    consultant.setEmail(id + "@example.org");
    consultant.setDisplayName(displayName);
    return consultant;
  }

  private CaseHandoverRequest pendingConsentRequest() {
    return CaseHandoverRequest.builder()
        .id(88L)
        .session(session)
        .requesterConsultant(requester)
        .previousConsultant(previous)
        .reasonCode("COUNSELLOR_ASKED_FOR_ADVICE")
        .reasonLabel("Advice needed")
        .explanation("Need a second opinion.")
        .status(CaseHandoverRequest.Status.PENDING_CLIENT_CONSENT)
        .accessType(CaseHandoverRequest.AccessType.CO_ACCESS)
        .maxAccessDurationMinutes(180)
        .clientConsentRequired(true)
        .policyAuthority("platform-admin-default-case-handover-policy")
        .auditOutcome("PENDING_CLIENT_CONSENT")
        .tenantId(7L)
        .build();
  }

  private CaseHandoverRequest grantedRequest(Consultant consultant) {
    return CaseHandoverRequest.builder()
        .id(99L)
        .session(session)
        .requesterConsultant(consultant)
        .previousConsultant(previous)
        .reasonCode("OTHER_EMERGENCY")
        .reasonLabel("Other emergency")
        .explanation("Already handled.")
        .status(CaseHandoverRequest.Status.GRANTED)
        .clientConsentRequired(false)
        .policyAuthority("platform-admin-default-case-handover-policy")
        .auditOutcome("ACCESS_GRANTED")
        .tenantId(7L)
        .build();
  }

  private CaseHandoverRequest grantedAdviceRequest() {
    return CaseHandoverRequest.builder()
        .id(100L)
        .session(session)
        .requesterConsultant(requester)
        .previousConsultant(previous)
        .reasonCode("COUNSELLOR_ASKED_FOR_ADVICE")
        .reasonLabel("Advice needed")
        .explanation("Second opinion")
        .status(CaseHandoverRequest.Status.GRANTED)
        .accessType(CaseHandoverRequest.AccessType.CO_ACCESS)
        .maxAccessDurationMinutes(180)
        .clientConsentRequired(true)
        .policyAuthority("platform-admin-default-case-handover-policy")
        .auditOutcome("ACCESS_GRANTED")
        .createdAt(LocalDateTime.of(2026, 8, 16, 7, 0))
        .resolvedAt(LocalDateTime.of(2026, 8, 16, 7, 0))
        .tenantId(7L)
        .build();
  }

  private de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverPolicies
      tenantPolicies(String germanLabel, int durationMinutes) {
    var mode =
        de.caritas.cob.userservice.tenantadminservice.generated.web.model.PermissionPolicyMode
            .ENFORCED;
    var enabled =
        new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                .BooleanPermissionPolicy(null)
            .value(true)
            .mode(mode);
    var labels =
        new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                .MultilingualTextPermissionPolicy(null)
            .value(java.util.Map.of("de", germanLabel, "en", "Advice needed"))
            .mode(mode);
    var templates =
        new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                .MultilingualTextPermissionPolicy(null)
            .value(
                java.util.Map.of(
                    "de", "{{newAdvisor}} kann {{duration}} zeitlich begrenzt mitlesen."))
            .mode(mode);
    var roles =
        new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                .StringListPermissionPolicy(null)
            .value(java.util.Set.of("CLIENT"))
            .mode(mode);
    var duration =
        new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                .IntegerPermissionPolicy(null)
            .value(durationMinutes)
            .mode(mode);
    var advice =
        new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                .CaseHandoverReasonPolicy()
            .code(
                de.caritas.cob.userservice.tenantadminservice.generated.web.model
                    .CaseHandoverReasonPolicy.CodeEnum.COUNSELLOR_ASKED_FOR_ADVICE)
            .labels(labels)
            .enabled(enabled)
            .accessAllowed(enabled)
            .clientConsentRequired(enabled)
            .approvalRoles(roles)
            .clientNotificationTemplates(templates)
            .maxAccessDurationMinutes(duration);
    return new de.caritas.cob.userservice.tenantadminservice.generated.web.model
            .CaseHandoverPolicies()
        .reasons(java.util.Map.of(advice.getCode().getValue(), advice));
  }

  @Test
  void updateReasonPolicies_toleratesDuplicateStoredCodes_andPersists() {
    // A hand-applied seed on an environment where 0057 was skipped can leave the
    // table with duplicate codes; the update must not blow up with a 500.
    when(caseHandoverReasonPolicyRepository.findAllByOrderByDisplayOrderAscCodeAsc())
        .thenReturn(
            List.of(
                reasonPolicy("COUNSELLOR_IS_ILL", "Counsellor is ill", false, true, true, 40),
                reasonPolicy(
                    "COUNSELLOR_IS_ILL", "Counsellor is ill (dup)", true, true, true, 40)));
    when(caseHandoverReasonPolicyRepository.saveAll(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    CaseHandoverReason requested =
        CaseHandoverReason.builder()
            .code("COUNSELLOR_IS_ILL")
            .label("Counsellor is ill")
            .clientConsentRequired(true)
            .accessAllowed(true)
            .enabled(false)
            .displayOrder(40)
            .policyAuthority("platform-admin-default-case-handover-policy")
            .build();

    caseHandoverService.updateReasonPolicies(List.of(requested));

    ArgumentCaptor<List<CaseHandoverReasonPolicy>> captor = ArgumentCaptor.forClass(List.class);
    verify(caseHandoverReasonPolicyRepository).saveAll(captor.capture());
    assertEquals(1, captor.getValue().size());
    assertEquals("COUNSELLOR_IS_ILL", captor.getValue().get(0).getCode());
    assertFalse(captor.getValue().get(0).getEnabled());
  }

  private CaseHandoverReasonPolicy reasonPolicy(
      String code,
      String label,
      boolean clientConsentRequired,
      boolean accessAllowed,
      boolean enabled,
      int displayOrder) {
    return CaseHandoverReasonPolicy.builder()
        .code(code)
        .label(label)
        .clientConsentRequired(clientConsentRequired)
        .accessAllowed(accessAllowed)
        .enabled(enabled)
        .displayOrder(displayOrder)
        .policyAuthority("platform-admin-default-case-handover-policy")
        .build();
  }
}
