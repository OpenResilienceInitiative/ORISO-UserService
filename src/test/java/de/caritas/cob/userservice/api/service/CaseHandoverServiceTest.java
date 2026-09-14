package de.caritas.cob.userservice.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.facade.SessionSupervisorFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.CaseHandoverReasonPolicy;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantTopic;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.SessionTopic;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CaseHandoverReasonPolicyRepository;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.CaseHandoverService.CaseHandoverReason;
import de.caritas.cob.userservice.api.service.CaseHandoverService.CaseHandoverStatus;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.CaseHandoverEmailNotification;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionOwnershipService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.tenant.TenantData;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CaseHandoverServiceTest {

  @InjectMocks private CaseHandoverService caseHandoverService;

  @Mock private CaseHandoverRequestRepository caseHandoverRequestRepository;
  @Mock private CaseHandoverReasonPolicyRepository caseHandoverReasonPolicyRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private SessionOwnershipService sessionOwnershipService;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private UserAccountService userAccountService;
  @Mock private EventNotificationService eventNotificationService;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private MatrixSessionSystemMessageService matrixSessionSystemMessageService;
  @Mock private SessionSupervisorFacade sessionSupervisorFacade;
  @Mock private CaseHandoverEmailNotification caseHandoverEmailNotification;
  @Mock private ConsultantService consultantService;
  @Mock private AuthenticatedUser authenticatedUser;

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
    asker.setTenantId(7L);

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
    when(consultantService.getConsultant("requester")).thenReturn(Optional.of(requester));
    when(authenticatedUser.getUserId()).thenReturn(requester.getId());
    when(userAccountService.retrieveValidatedUser()).thenReturn(asker);
    when(sessionRepository.findById(123L)).thenReturn(Optional.of(session));
    when(sessionRepository.findByIdForUpdate(123L)).thenReturn(Optional.of(session));
    when(sessionOwnershipService.updateOwner(
            any(Session.class), any(Consultant.class), any(SessionStatus.class), any()))
        .thenAnswer(
            invocation -> {
              Session target = invocation.getArgument(0);
              Consultant owner = invocation.getArgument(1);
              target.setConsultant(owner);
              target.setOwnershipRevision(target.getOwnershipRevision() + 1);
              return new SessionOwnershipService.OwnershipChange(
                  target.getId(), owner.getId(), target.getOwnershipRevision());
            });
    when(caseHandoverReasonPolicyRepository.findByEnabledTrueOrderByDisplayOrderAscCodeAsc())
        .thenReturn(List.of());
    when(caseHandoverReasonPolicyRepository.findAllByOrderByDisplayOrderAscCodeAsc())
        .thenReturn(List.of());
    when(caseHandoverRequestRepository.findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
            123L, "requester"))
        .thenReturn(List.of());
    when(caseHandoverRequestRepository.findBySessionIdAndStatusOrderByCreatedAtDesc(
            123L, CaseHandoverRequest.Status.GRANTED))
        .thenReturn(List.of());
    when(caseHandoverRequestRepository.save(any(CaseHandoverRequest.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void requestAccess_grantsAndActivatesCounsellor_WhenPolicyDoesNotRequireClientConsent() {
    CaseHandoverStatus status = requestAccess(123L, "OTHER_EMERGENCY", "Colleague is unavailable.");

    assertEquals("GRANTED", status.getStatus());
    assertTrue(status.isCanViewContent());
    assertFalse(status.isClientConsentRequired());
    assertEquals(requester, session.getConsultant());
    verify(sessionOwnershipService)
        .updateOwner(eq(session), eq(requester), eq(SessionStatus.IN_PROGRESS), any());
    verify(eventNotificationService, atLeastOnce())
        .createEvent(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void requestAccess_identicalOperationReturnsStoredPullResultWithoutRepeatingEffects() {
    UUID operationId = UUID.fromString("68fc1ae0-ed6d-4a28-909d-2592f6891b21");
    CaseHandoverRequest stored = grantedRequest(requester);
    stored.setOperationId(operationId);
    stored.setExpectedOwnershipRevision(0L);
    stored.setExplanation("Urgent cover");
    when(caseHandoverRequestRepository.findByInitiatorConsultantIdAndOperationId(
            "requester", operationId))
        .thenReturn(Optional.of(stored));

    CaseHandoverStatus result =
        requestAccess(123L, "OTHER_EMERGENCY", "Urgent cover", 0L, operationId);

    assertEquals("GRANTED", result.getStatus());
    verify(caseHandoverRequestRepository, never()).save(any());
    verify(sessionOwnershipService, never()).updateOwner(any(), any(), any(), any());
  }

  @Test
  void requestAccess_sameOperationWithChangedPullPayloadConflicts() {
    UUID operationId = UUID.fromString("68fc1ae0-ed6d-4a28-909d-2592f6891b21");
    CaseHandoverRequest stored = grantedRequest(requester);
    stored.setOperationId(operationId);
    stored.setExpectedOwnershipRevision(0L);
    stored.setExplanation("Original explanation");
    when(caseHandoverRequestRepository.findByInitiatorConsultantIdAndOperationId(
            "requester", operationId))
        .thenReturn(Optional.of(stored));

    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class,
        () -> requestAccess(123L, "OTHER_EMERGENCY", "Changed explanation", 0L, operationId));
  }

  @Test
  void requestAccess_rejectsFreshOperationFromStaleOwnershipPeriod() {
    session.setOwnershipRevision(2L);

    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class,
        () ->
            requestAccess(
                123L,
                "OTHER_EMERGENCY",
                "Urgent cover",
                1L,
                UUID.fromString("f0378720-261a-439b-9bb5-f27510191544")));

    verify(caseHandoverRequestRepository, never()).save(any());
  }

  @Test
  void requestAccess_rejectsReturningCaseToAPreviousOwner() {
    when(caseHandoverRequestRepository.findByPreviousConsultantId("requester"))
        .thenReturn(
            List.of(
                CaseHandoverRequest.builder()
                    .session(session)
                    .status(CaseHandoverRequest.Status.GRANTED)
                    .build()));

    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class,
        () ->
            requestAccess(
                123L,
                "OTHER_EMERGENCY",
                "Urgent cover",
                0L,
                UUID.fromString("6a772b34-954f-46a0-80a9-582a78d728d7")));

    verify(caseHandoverRequestRepository, never()).save(any());
    verify(sessionOwnershipService, never()).updateOwner(any(), any(), any(), any());
  }

  @Test
  void requestAccess_rejectsFreshRequesterFromAnotherPositiveTenantBeforeAnyEffects() {
    requester.setTenantId(8L);

    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException.class,
        () ->
            requestAccess(
                123L,
                "OTHER_EMERGENCY",
                "Urgent cover",
                0L,
                UUID.fromString("3ada2ad1-86a3-4480-aef2-e5231f25e1d7")));

    verify(caseHandoverRequestRepository, never()).save(any());
    verify(sessionRepository, never()).save(any());
    verifyNoInteractions(
        sessionOwnershipService,
        matrixSynapseService,
        matrixSessionSystemMessageService,
        eventNotificationService,
        caseHandoverEmailNotification);
  }

  @Test
  void requestAccess_rejectsFreshAbsentRequesterBeforeAnyEffects() {
    requester.setAbsent(true);

    assertThrows(
        ForbiddenException.class,
        () ->
            requestAccess(
                123L,
                "OTHER_EMERGENCY",
                "Urgent cover",
                0L,
                UUID.fromString("68762962-2cfc-4b13-91b4-bc31e66f9de0")));

    verify(caseHandoverRequestRepository, never()).save(any());
    verify(sessionRepository, never()).save(any());
    verifyNoInteractions(
        sessionOwnershipService,
        matrixSynapseService,
        matrixSessionSystemMessageService,
        eventNotificationService,
        caseHandoverEmailNotification);
  }

  @Test
  void createOffer_persistsRecipientPendingWithoutGrantClientOrMailEffects() {
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    UUID operationId = UUID.fromString("ca40d769-e617-43a8-8c89-5da47316672d");
    session.setConsultant(requester);
    when(consultantService.getConsultant("recipient")).thenReturn(Optional.of(recipient));
    when(caseHandoverRequestRepository.save(any()))
        .thenAnswer(
            invocation -> {
              CaseHandoverRequest saved = invocation.getArgument(0);
              saved.setId(501L);
              return saved;
            });

    CaseHandoverStatus result =
        caseHandoverService.createOffer(
            123L, "recipient", "OTHER_EMERGENCY", null, 0L, operationId);

    assertEquals("PENDING_RECIPIENT_ACCEPTANCE", result.getStatus());
    assertEquals("PUSH", result.getDirection());
    assertEquals("requester", result.getInitiatorConsultantId());
    assertEquals("recipient", result.getRecipientConsultantId());
    assertEquals(operationId, result.getOperationId());
    assertEquals(requester, session.getConsultant());
    verify(sessionOwnershipService, never()).updateOwner(any(), any(), any(), any());
    verify(caseHandoverEmailNotification, never())
        .ownershipGranted(any(), any(), any(), any(), any());
    verify(caseHandoverEmailNotification, never()).takeoverConsentRequested(any(), any(), any());
    verify(eventNotificationService)
        .createEventOnce(
            eq("case-handover-offer:501"),
            eq("recipient"),
            eq("case.handover.offer.received"),
            any(),
            any(),
            any(),
            any(),
            eq("/sessions/consultant/sessionView/session/123?caseHandoverRequestId=501"),
            eq(123L),
            eq(7L));
  }

  @Test
  void createOffer_rejectsEligibleRecipientFromAnotherTenant() {
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    recipient.setTenantId(8L);
    session.setConsultant(requester);
    when(consultantService.getConsultant("recipient")).thenReturn(Optional.of(recipient));

    assertThrows(
        ForbiddenException.class,
        () ->
            caseHandoverService.createOffer(
                123L,
                "recipient",
                "OTHER_EMERGENCY",
                null,
                0L,
                UUID.fromString("40273e18-97ec-4303-a6ec-98ba75091d3d")));

    verify(caseHandoverRequestRepository, never()).save(any());
  }

  @Test
  void createOffer_rejectsReturningCaseToAPreviousOwner() {
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    session.setConsultant(requester);
    when(consultantService.getConsultant("recipient")).thenReturn(Optional.of(recipient));
    when(caseHandoverRequestRepository.findByPreviousConsultantId("recipient"))
        .thenReturn(
            List.of(
                CaseHandoverRequest.builder()
                    .session(session)
                    .status(CaseHandoverRequest.Status.GRANTED)
                    .build()));

    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class,
        () ->
            caseHandoverService.createOffer(
                123L,
                "recipient",
                "OTHER_EMERGENCY",
                null,
                0L,
                UUID.fromString("82703c1d-536d-4f2e-9d49-ad18742a46a3")));

    verify(caseHandoverRequestRepository, never()).save(any());
  }

  @Test
  void requestAccess_adviceReasonFailsClosedWithoutStartingConsentOrGrant() {
    CaseHandoverStatus result =
        requestAccess(
            123L,
            "COUNSELLOR_ASKED_FOR_ADVICE",
            "Need advice, not ownership.",
            0L,
            UUID.fromString("40cd6572-b4b3-4b2f-b869-ac944142f110"));

    assertEquals("DENIED", result.getStatus());
    verify(sessionOwnershipService, never()).updateOwner(any(), any(), any(), any());
    verify(caseHandoverEmailNotification, never()).takeoverConsentRequested(any(), any(), any());
  }

  @Test
  void createOffer_adviceReasonFailsClosedBeforeRecipientOffer() {
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    session.setConsultant(requester);
    when(consultantService.getConsultant("recipient")).thenReturn(Optional.of(recipient));

    assertThrows(
        ForbiddenException.class,
        () ->
            caseHandoverService.createOffer(
                123L,
                "recipient",
                "COUNSELLOR_ASKED_FOR_ADVICE",
                null,
                0L,
                UUID.fromString("ab77f4f4-e88f-4eed-88ab-7f454b3d8fa6")));

    verify(caseHandoverRequestRepository, never()).save(any());
  }

  @Test
  void createOffer_identicalReplaySucceedsAfterInitiatorIsNoLongerOwner() {
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    UUID operationId = UUID.fromString("ca40d769-e617-43a8-8c89-5da47316672d");
    CaseHandoverRequest stored = pushRequest(recipient, operationId);
    stored.setStatus(CaseHandoverRequest.Status.GRANTED);
    session.setConsultant(recipient);
    session.setOwnershipRevision(1L);
    when(caseHandoverRequestRepository.findByInitiatorConsultantIdAndOperationId(
            "requester", operationId))
        .thenReturn(Optional.of(stored));

    CaseHandoverStatus result =
        caseHandoverService.createOffer(
            123L, "recipient", "OTHER_EMERGENCY", null, 0L, operationId);

    assertEquals("GRANTED", result.getStatus());
    verify(caseHandoverRequestRepository, never()).save(any());
    verify(sessionOwnershipService, never()).updateOwner(any(), any(), any(), any());
  }

  @Test
  void createOffer_sameOperationWithChangedTargetConflicts() {
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    UUID operationId = UUID.fromString("ca40d769-e617-43a8-8c89-5da47316672d");
    CaseHandoverRequest stored = pushRequest(recipient, operationId);
    when(caseHandoverRequestRepository.findByInitiatorConsultantIdAndOperationId(
            "requester", operationId))
        .thenReturn(Optional.of(stored));

    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class,
        () ->
            caseHandoverService.createOffer(
                123L, "other", "OTHER_EMERGENCY", null, 0L, operationId));
  }

  @Test
  void createOffer_rollbackDoesNotPublishRecipientEvent() {
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    session.setConsultant(requester);
    when(consultantService.getConsultant("recipient")).thenReturn(Optional.of(recipient));
    assignPersistedRequestId(501L);
    TransactionSynchronizationManager.initSynchronization();
    try {
      caseHandoverService.createOffer(
          123L,
          "recipient",
          "OTHER_EMERGENCY",
          null,
          0L,
          UUID.fromString("ca40d769-e617-43a8-8c89-5da47316672d"));

      verify(eventNotificationService, never())
          .createEventOnce(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void createOffer_afterCommitPublishesRecipientEventExactlyOnce() {
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    session.setConsultant(requester);
    when(consultantService.getConsultant("recipient")).thenReturn(Optional.of(recipient));
    assignPersistedRequestId(501L);
    TransactionSynchronizationManager.initSynchronization();
    try {
      caseHandoverService.createOffer(
          123L,
          "recipient",
          "OTHER_EMERGENCY",
          null,
          0L,
          UUID.fromString("ca40d769-e617-43a8-8c89-5da47316672d"));
      var callbacks = TransactionSynchronizationManager.getSynchronizations();
      assertEquals(1, callbacks.size());
      callbacks.get(0).afterCommit();
      callbacks.get(0).afterCompletion(0);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    verify(eventNotificationService, times(1))
        .createEventOnce(
            eq("case-handover-offer:501"),
            any(),
            eq("case.handover.offer.received"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  void resolveRecipientDecision_declineIsTerminalAndHasNoGrantEffects() {
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    CaseHandoverRequest request =
        pushRequest(recipient, UUID.fromString("ca40d769-e617-43a8-8c89-5da47316672d"));
    session.setConsultant(requester);
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(recipient);
    when(consultantService.getConsultant("recipient")).thenReturn(Optional.of(recipient));
    when(authenticatedUser.getUserId()).thenReturn(recipient.getId());
    when(caseHandoverRequestRepository.findByIdAndSessionIdForUpdate(501L, 123L))
        .thenReturn(Optional.of(request));

    CaseHandoverStatus declined = caseHandoverService.resolveRecipientDecision(123L, 501L, false);
    CaseHandoverStatus oppositeReplay =
        caseHandoverService.resolveRecipientDecision(123L, 501L, true);

    assertEquals("RECIPIENT_DECLINED", declined.getStatus());
    assertEquals("RECIPIENT_DECLINED", oppositeReplay.getStatus());
    assertEquals(requester, session.getConsultant());
    verify(sessionOwnershipService, never()).updateOwner(any(), any(), any(), any());
    verify(caseHandoverEmailNotification, never())
        .ownershipGranted(any(), any(), any(), any(), any());
  }

  @Test
  void resolveRecipientDecision_acceptsThenAppliesCurrentNoConsentPolicyOnce() {
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    CaseHandoverRequest request =
        pushRequest(recipient, UUID.fromString("ca40d769-e617-43a8-8c89-5da47316672d"));
    session.setConsultant(requester);
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(recipient);
    when(consultantService.getConsultant("recipient")).thenReturn(Optional.of(recipient));
    when(authenticatedUser.getUserId()).thenReturn(recipient.getId());
    when(caseHandoverRequestRepository.findByIdAndSessionIdForUpdate(501L, 123L))
        .thenReturn(Optional.of(request));

    CaseHandoverStatus granted = caseHandoverService.resolveRecipientDecision(123L, 501L, true);
    CaseHandoverStatus replay = caseHandoverService.resolveRecipientDecision(123L, 501L, true);

    assertEquals("GRANTED", granted.getStatus());
    assertEquals("GRANTED", replay.getStatus());
    assertEquals(recipient, session.getConsultant());
    verify(sessionOwnershipService, times(1))
        .updateOwner(eq(session), eq(recipient), eq(SessionStatus.IN_PROGRESS), any());
  }

  @Test
  void getRequestStatus_allowsExactNonTeamRecipientWithoutGrantingContent() {
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    CaseHandoverRequest request =
        pushRequest(recipient, UUID.fromString("ca40d769-e617-43a8-8c89-5da47316672d"));
    session.setTeamSession(false);
    session.setConsultant(requester);
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn(recipient.getId());
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(recipient);
    when(caseHandoverRequestRepository.findByIdAndSessionId(501L, 123L))
        .thenReturn(Optional.of(request));

    CaseHandoverStatus result = caseHandoverService.getRequestStatus(123L, 501L);

    assertEquals("PENDING_RECIPIENT_ACCEPTANCE", result.getStatus());
    assertEquals("recipient", result.getRecipientConsultantId());
    assertFalse(result.isCanViewContent());
  }

  @Test
  void getRequestStatus_hidesCrossTenantRequestFromOtherwiseMatchingRecipient() {
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    CaseHandoverRequest request =
        pushRequest(recipient, UUID.fromString("ca40d769-e617-43a8-8c89-5da47316672d"));
    request.setTenantId(8L);
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(recipient);
    when(caseHandoverRequestRepository.findByIdAndSessionId(501L, 123L))
        .thenReturn(Optional.of(request));

    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException.class,
        () -> caseHandoverService.getRequestStatus(123L, 501L));
  }

  @Test
  void getRequestStatus_hidesRequestWhenMatchingRecipientNowBelongsToAnotherTenant() {
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    recipient.setTenantId(8L);
    CaseHandoverRequest request =
        pushRequest(recipient, UUID.fromString("ca40d769-e617-43a8-8c89-5da47316672d"));
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(recipient);
    when(caseHandoverRequestRepository.findByIdAndSessionId(501L, 123L))
        .thenReturn(Optional.of(request));

    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException.class,
        () -> caseHandoverService.getRequestStatus(123L, 501L));
  }

  @Test
  void createOffer_replayIsHiddenWhenInitiatorNowBelongsToAnotherTenant() {
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    UUID operationId = UUID.fromString("ca40d769-e617-43a8-8c89-5da47316672d");
    CaseHandoverRequest stored = pushRequest(recipient, operationId);
    requester.setTenantId(8L);
    when(caseHandoverRequestRepository.findByInitiatorConsultantIdAndOperationId(
            "requester", operationId))
        .thenReturn(Optional.of(stored));

    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException.class,
        () ->
            caseHandoverService.createOffer(
                123L, "recipient", "OTHER_EMERGENCY", null, 0L, operationId));
  }

  @Test
  void resolveRecipientDecision_hidesOfferWhenRecipientNowBelongsToAnotherTenant() {
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    recipient.setTenantId(8L);
    CaseHandoverRequest request =
        pushRequest(recipient, UUID.fromString("ca40d769-e617-43a8-8c89-5da47316672d"));
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(recipient);
    when(caseHandoverRequestRepository.findByIdAndSessionIdForUpdate(501L, 123L))
        .thenReturn(Optional.of(request));

    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException.class,
        () -> caseHandoverService.resolveRecipientDecision(123L, 501L, false));
  }

  @Test
  void getRequestStatus_afterGrantUsesViewerIdentityForContentGate() {
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    CaseHandoverRequest request =
        pushRequest(recipient, UUID.fromString("ca40d769-e617-43a8-8c89-5da47316672d"));
    request.setStatus(CaseHandoverRequest.Status.GRANTED);
    session.setConsultant(recipient);
    session.setOwnershipRevision(1L);
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(requester);
    when(authenticatedUser.getUserId()).thenReturn(requester.getId());
    when(caseHandoverRequestRepository.findByIdAndSessionId(501L, 123L))
        .thenReturn(Optional.of(request));

    assertFalse(caseHandoverService.getRequestStatus(123L, 501L).isCanViewContent());

    when(userAccountService.retrieveValidatedConsultant()).thenReturn(recipient);
    when(authenticatedUser.getUserId()).thenReturn(recipient.getId());
    assertTrue(caseHandoverService.getRequestStatus(123L, 501L).isCanViewContent());
  }

  @Test
  void requestAccess_sendsOneGrantedEmailIntent_WhenOwnershipIsGrantedImmediately()
      throws Exception {
    UUID requesterId = prepareDeliverableHandoverMail();
    assignPersistedRequestId(81L);

    requestAccess(123L, "OTHER_EMERGENCY", "Colleague is unavailable.");

    verify(caseHandoverEmailNotification, times(1))
        .ownershipGranted(
            81L,
            "!handover-room:matrix",
            requesterId,
            "Previous Counsellor",
            new TenantData(7L, null));
    verify(caseHandoverEmailNotification, never()).takeoverConsentRequested(any(), any(), any());
  }

  @Test
  void requestAccess_sendsOneConsentEmailIntent_WhenClientConsentIsPending() {
    session.setMatrixRoomId("!handover-room:matrix");
    assignPersistedRequestId(82L);
    givenIllnessRequiresClientConsent();

    requestAccess(123L, "COUNSELLOR_IS_ILL", "Need temporary cover.");

    verify(caseHandoverEmailNotification, times(1))
        .takeoverConsentRequested(82L, "!handover-room:matrix", new TenantData(7L, null));
    verify(caseHandoverEmailNotification, never())
        .ownershipGranted(any(), any(), any(), any(), any());
  }

  @Test
  void resolveClientConsent_sendsOneGrantedEmailIntent_WhenClientApproves() throws Exception {
    UUID requesterId = prepareDeliverableHandoverMail();
    CaseHandoverRequest request = pendingConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));

    caseHandoverService.resolveClientConsent(123L, 88L, true);

    verify(caseHandoverEmailNotification, times(1))
        .ownershipGranted(
            88L,
            "!handover-room:matrix",
            requesterId,
            "Previous Counsellor",
            new TenantData(7L, null));
    verify(caseHandoverEmailNotification, never()).takeoverConsentRequested(any(), any(), any());
  }

  @Test
  void resolveClientConsent_notifiesRequesterOnce_WhenClientApprovesAndDecisionIsRepeated() {
    CaseHandoverRequest request = pendingConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));

    caseHandoverService.resolveClientConsent(123L, 88L, true);
    caseHandoverService.resolveClientConsent(123L, 88L, true);

    verify(eventNotificationService, times(1))
        .createEvent(
            eq("requester"),
            eq("case.handover.granted"),
            eq(EventNotificationService.CATEGORY_SYSTEM),
            anyString(),
            anyString(),
            any(),
            any(),
            eq(123L),
            eq(7L));
  }

  @Test
  void requestAccess_sendsNoEmailIntent_WhenPolicyDeniesTheRequest() {
    session.setMatrixRoomId("!handover-room:matrix");
    when(caseHandoverReasonPolicyRepository.findByEnabledTrueOrderByDisplayOrderAscCodeAsc())
        .thenReturn(
            List.of(reasonPolicy("OTHER_EMERGENCY", "Other emergency", false, false, true, 30)));

    requestAccess(123L, "OTHER_EMERGENCY", "Needs cover.");

    verifyNoInteractions(caseHandoverEmailNotification);
  }

  @Test
  void resolveClientConsent_sendsNoEmailIntent_WhenClientDeclines() {
    session.setMatrixRoomId("!handover-room:matrix");
    CaseHandoverRequest request = pendingConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));

    caseHandoverService.resolveClientConsent(123L, 88L, false);

    verifyNoInteractions(caseHandoverEmailNotification);
  }

  @Test
  void resolveClientConsent_serializesOnSessionBeforeReadingRequest() {
    CaseHandoverRequest request = pendingConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));

    caseHandoverService.resolveClientConsent(123L, 88L, false);

    var order = inOrder(sessionRepository, caseHandoverRequestRepository);
    order.verify(sessionRepository).findByIdForUpdate(123L);
    order.verify(caseHandoverRequestRepository).findByIdAndSessionId(88L, 123L);
  }

  @Test
  void requestAccess_sendsNoNewEmailIntent_WhenAnOpenRequestAlreadyExists() {
    session.setMatrixRoomId("!handover-room:matrix");
    when(caseHandoverRequestRepository.findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
            123L, "requester"))
        .thenReturn(List.of(pendingConsentRequest()));

    requestAccess(123L, "COUNSELLOR_IS_ILL", "Repeated request.");

    verifyNoInteractions(caseHandoverEmailNotification);
  }

  @Test
  void requestAccess_preservesGrantedOutcomeWithoutEmail_WhenMatrixRoomIsBlank() {
    session.setMatrixRoomId("  ");
    assignPersistedRequestId(83L);

    CaseHandoverStatus status = requestAccess(123L, "OTHER_EMERGENCY", "Colleague is unavailable.");

    assertEquals("GRANTED", status.getStatus());
    assertEquals(requester, session.getConsultant());
    verifyNoInteractions(caseHandoverEmailNotification);
  }

  @Test
  void requestAccess_preservesGrantedOutcomeWithoutEmail_WhenSessionTenantIsMissing()
      throws Exception {
    prepareDeliverableHandoverMail();
    session.setTenantId(null);
    assignPersistedRequestId(84L);

    CaseHandoverStatus status = requestAccess(123L, "OTHER_EMERGENCY", "Colleague is unavailable.");

    assertEquals("GRANTED", status.getStatus());
    assertEquals(requester, session.getConsultant());
    verifyNoInteractions(caseHandoverEmailNotification);
  }

  @Test
  void requestAccess_preservesGrantedOutcomeWithoutEmail_WhenSessionTenantIsTechnical()
      throws Exception {
    prepareDeliverableHandoverMail();
    session.setTenantId(0L);
    assignPersistedRequestId(85L);

    CaseHandoverStatus status = requestAccess(123L, "OTHER_EMERGENCY", "Colleague is unavailable.");

    assertEquals("GRANTED", status.getStatus());
    assertEquals(requester, session.getConsultant());
    verifyNoInteractions(caseHandoverEmailNotification);
  }

  /**
   * ADR-008 "Supervision (auto-assigned)": a takeover hands the case to a new owner, so the new
   * owner's standing supervisor has to attach. Before this, only the enquiry-accept path did, and a
   * case that changed hands silently ran unsupervised.
   */
  @Test
  void requestAccess_attachesTheNewOwnersStandingSupervisor_WhenGranted() {
    requestAccess(123L, "OTHER_EMERGENCY", "Colleague is unavailable.");

    verify(sessionSupervisorFacade).attachStandingSupervisorIfAssigned(123L, requester);
  }

  @Test
  void requestAccess_doesNotAttachAStandingSupervisor_WhenTheHandoverIsNotGranted() {
    when(caseHandoverReasonPolicyRepository.findByEnabledTrueOrderByDisplayOrderAscCodeAsc())
        .thenReturn(
            List.of(reasonPolicy("OTHER_EMERGENCY", "Other emergency", false, false, true, 30)));

    requestAccess(123L, "OTHER_EMERGENCY", "Needs cover.");

    verify(sessionSupervisorFacade, never()).attachStandingSupervisorIfAssigned(any(), any());
  }

  /**
   * The attach must not run inside this service's transaction. {@code addSupervisor} is itself
   * transactional, so an exception it raises there (client opted out, supervisor already on the
   * case, no Matrix user id) would mark the shared transaction rollback-only and kill the handover
   * at commit — even though the facade swallows it. Deferring to after-commit is the whole point,
   * so assert the deferral, not just the call.
   *
   * <p>It must also defer to the REQUIRES_NEW entry point, not the plain one: during afterCommit
   * the committed transaction's resources are still bound to the thread, so a write through the
   * plain method joins a transaction that can no longer commit and the SessionSupervisor row is
   * lost after Matrix access has already been granted.
   */
  @Test
  void requestAccess_defersTheSupervisorAttachToANewTransactionAfterTheHandoverHasCommitted() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      requestAccess(123L, "OTHER_EMERGENCY", "Colleague is unavailable.");

      verify(sessionSupervisorFacade, never())
          .attachStandingSupervisorInNewTransaction(any(), any());

      TransactionSynchronizationManager.getSynchronizations()
          .forEach(synchronization -> synchronization.afterCommit());

      verify(sessionSupervisorFacade).attachStandingSupervisorInNewTransaction(123L, requester);
      verify(sessionSupervisorFacade, never()).attachStandingSupervisorIfAssigned(any(), any());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  /**
   * #1010 task 1a: the handover explanation is free text a counsellor writes and can reference case
   * content. It used to be formatted into {@code event_notification.text}, a table with no
   * retention that outlives the case, which made it the one place counselling content sat in
   * plaintext. The client reads it from the handover request instead.
   */
  @Test
  void requestAccess_neverCopiesTheExplanationIntoAStoredNotification() {
    requestAccess(123L, "COUNSELLOR_IS_ILL", "Client disclosed self-harm.");

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

  @Test
  void requestAccess_usesOnlyGenericLocalizedTextForAskerNotification() {
    when(eventNotificationService.buildCaseHandoverParams(
            eq(session), anyString(), isNull(), isNull(), isNull()))
        .thenReturn("{\"audience\":\"asker\"}");

    requestAccess(123L, "COUNSELLOR_IS_ILL", "Client disclosed sensitive information.");

    verify(eventNotificationService)
        .createEvent(
            eq("asker"),
            eq("case.handover.granted"),
            eq(EventNotificationService.CATEGORY_SYSTEM),
            anyString(),
            eq(
                "Requesting Counsellor hat deinen Fall übernommen und führt deine Beratung ab jetzt weiter."),
            eq("{\"audience\":\"asker\"}"),
            anyString(),
            eq(123L),
            eq(7L));
  }

  @ParameterizedTest
  @CsvSource({
    "de, 'Zugriffsanfrage einer Beratungsperson', 'Requesting Counsellor bittet um Zugriff auf deinen Fall. Deine Zustimmung ist erforderlich.'",
    "en, 'Counsellor access request', 'Requesting Counsellor requested access to your case. Your consent is required.'",
    "fr, 'Demande d’accès d’un conseiller ou d’une conseillère', 'Requesting Counsellor demande l’accès à votre dossier. Votre consentement est requis.'",
    "ru, 'Запрос консультанта на доступ', 'Requesting Counsellor запросил(а) доступ к вашему делу. Требуется ваше согласие.'",
    "tr, 'Danışman erişim talebi', 'Requesting Counsellor vakanıza erişim istedi. Onayınız gerekiyor.'",
    "uk, 'Запит консультанта на доступ', 'Requesting Counsellor запитує доступ до вашої справи. Потрібна ваша згода.'",
    "ti, 'ናይ ኣማኻሪ ናይ ምእታው ሕቶ', 'Requesting Counsellor ናብ ጉዳይካ ክኣቱ ሓቲቱ። ፍቓድካ የድሊ።'"
  })
  void requestAccess_keepsPendingConsentReasonOutOfLocalizedAskerNotification(
      String language, String expectedTitle, String expectedDescription) {
    givenIllnessRequiresClientConsent();
    session.setLanguageCode(LanguageCode.getByCode(language));
    when(caseHandoverRequestRepository.save(any(CaseHandoverRequest.class)))
        .thenAnswer(
            invocation -> {
              CaseHandoverRequest saved = invocation.getArgument(0);
              saved.setId(88L);
              return saved;
            });
    when(eventNotificationService.buildCaseHandoverParams(
            eq(session), anyString(), isNull(), isNull(), eq(88L)))
        .thenReturn("{\"audience\":\"asker\"}");

    requestAccess(123L, "COUNSELLOR_IS_ILL", "Client disclosed sensitive information.");

    verify(eventNotificationService)
        .createEvent(
            eq("asker"),
            eq("case.handover.consent.requested"),
            eq(EventNotificationService.CATEGORY_SYSTEM),
            eq(expectedTitle),
            eq(expectedDescription),
            eq("{\"audience\":\"asker\"}"),
            anyString(),
            eq(123L),
            eq(7L));
    verify(eventNotificationService)
        .buildCaseHandoverParams(eq(session), anyString(), isNull(), isNull(), eq(88L));
  }

  @ParameterizedTest
  @CsvSource({
    "de, 'Neue Beratungsperson hat deinen Fall übernommen', 'Requesting Counsellor hat deinen Fall übernommen und führt deine Beratung ab jetzt weiter.'",
    "en, 'New counsellor took over your case', 'Requesting Counsellor has taken over your case and will continue your counselling from now on.'",
    "fr, 'Un nouveau conseiller ou une nouvelle conseillère a repris votre dossier', 'Requesting Counsellor a repris votre dossier et poursuivra désormais votre accompagnement.'",
    "ru, 'Новый консультант принял ваше дело', 'Requesting Counsellor принял(а) ваше дело и с этого момента продолжит консультирование.'",
    "tr, 'Yeni bir danışman vakanızı devraldı', 'Requesting Counsellor vakanızı devraldı ve bundan sonra danışmanlığınıza devam edecek.'",
    "uk, 'Новий консультант перейняв вашу справу', 'Requesting Counsellor перейняв(-ла) вашу справу й відтепер продовжуватиме консультування.'",
    "ti, 'ሓድሽ ኣማኻሪ ጉዳይካ ተረኪቡ', 'Requesting Counsellor ጉዳይካ ተረኪቡ ካብ ሕጂ ንደሓር ምኽሪ ክቕጽል እዩ።'"
  })
  void requestAccess_providesSafeClientDescriptionForEverySupportedLanguage(
      String language, String expectedTitle, String expectedDescription) {
    session.setLanguageCode(LanguageCode.getByCode(language));
    ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
    when(eventNotificationService.buildCaseHandoverParams(
            eq(session), anyString(), isNull(), isNull(), isNull()))
        .thenReturn("{\"audience\":\"asker\"}");

    requestAccess(123L, "COUNSELLOR_IS_ILL", "Client disclosed sensitive information.");

    verify(matrixSessionSystemMessageService)
        .postCaseHandoverGrantedMessage(eq(session), anyString(), description.capture());
    assertEquals(expectedDescription, description.getValue());
    verify(eventNotificationService)
        .createEvent(
            eq("asker"),
            eq("case.handover.granted"),
            eq(EventNotificationService.CATEGORY_SYSTEM),
            eq(expectedTitle),
            eq(expectedDescription),
            eq("{\"audience\":\"asker\"}"),
            anyString(),
            eq(123L),
            eq(7L));
    verify(eventNotificationService)
        .buildCaseHandoverParams(eq(session), anyString(), isNull(), isNull(), isNull());
  }

  @Test
  void requestAccess_fallsBackToGermanClientCopyWhenLanguageIsMissing() {
    session.setLanguageCode(null);
    when(eventNotificationService.buildCaseHandoverParams(
            eq(session), anyString(), isNull(), isNull(), isNull()))
        .thenReturn("{\"audience\":\"asker\"}");

    requestAccess(123L, "COUNSELLOR_IS_ILL", "Client disclosed sensitive information.");

    verify(matrixSessionSystemMessageService)
        .postCaseHandoverGrantedMessage(
            eq(session),
            eq("Requesting Counsellor"),
            eq(
                "Requesting Counsellor hat deinen Fall übernommen und führt deine Beratung ab jetzt weiter."));
    verify(eventNotificationService)
        .createEvent(
            eq("asker"),
            eq("case.handover.granted"),
            eq(EventNotificationService.CATEGORY_SYSTEM),
            eq("Neue Beratungsperson hat deinen Fall übernommen"),
            eq(
                "Requesting Counsellor hat deinen Fall übernommen und führt deine Beratung ab jetzt weiter."),
            eq("{\"audience\":\"asker\"}"),
            anyString(),
            eq(123L),
            eq(7L));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "COUNSELLOR_ON_HOLIDAY",
        "OTHER_EMERGENCY",
        "COUNSELLOR_IS_ILL",
        "COUNSELLOR_LEFT"
      })
  void requestAccess_neverDerivesClientDescriptionFromInternalReason(String reasonCode) {
    ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);

    requestAccess(123L, reasonCode, "Client disclosed sensitive information.");

    verify(matrixSessionSystemMessageService)
        .postCaseHandoverGrantedMessage(eq(session), anyString(), description.capture());
    assertEquals(
        "Requesting Counsellor hat deinen Fall übernommen und führt deine Beratung ab jetzt weiter.",
        description.getValue());
  }

  /** The reason stays — it is a configured label, not free text — and moves into params. */
  @Test
  void requestAccess_carriesRequesterAndReasonAsParams() {
    requestAccess(123L, "COUNSELLOR_IS_ILL", "Illness cover.");

    verify(eventNotificationService, atLeastOnce())
        .buildCaseHandoverParams(any(), anyString(), eq("COUNSELLOR_IS_ILL"), any(), any());
  }

  @Test
  void requestAccess_invitesRequesterToExistingMatrixRoom_WhenGranted() throws Exception {
    assignValidRequesterId();
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    previous.setMatrixUserId("@previous:matrix");
    when(matrixSynapseService.loginAsUserAccessToken("@previous:matrix"))
        .thenReturn("previous-token");
    when(matrixSynapseService.loginAsUserAccessToken("@requester:matrix"))
        .thenReturn("requester-token");
    when(matrixSynapseService.joinRoom("!room:matrix", "requester-token")).thenReturn(true);

    requestAccess(123L, "OTHER_EMERGENCY", "Colleague is unavailable.");

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
    assignValidRequesterId();
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

    CaseHandoverStatus status = requestAccess(123L, "OTHER_EMERGENCY", "Colleague is unavailable.");

    assertEquals("GRANTED", status.getStatus());
    assertEquals(requester, session.getConsultant());
    verify(matrixSynapseService).joinRoom("!room:matrix", "requester-token");
    verify(sessionOwnershipService)
        .updateOwner(eq(session), eq(requester), eq(SessionStatus.IN_PROGRESS), any());
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
        () -> requestAccess(123L, "OTHER_EMERGENCY", "Colleague is unavailable."));

    assertEquals(previous, session.getConsultant());
    verify(sessionRepository, never()).save(session);
  }

  @Test
  void requestAccess_postsCaseHandoverSystemMessage_WhenGranted() throws Exception {
    assignValidRequesterId();
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    previous.setMatrixUserId("@previous:matrix");
    when(matrixSynapseService.loginAsUserAccessToken(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn("token");
    when(matrixSynapseService.joinRoom("!room:matrix", "token")).thenReturn(true);

    requestAccess(123L, "OTHER_EMERGENCY", "Colleague is unavailable.");

    verify(matrixSessionSystemMessageService)
        .postCaseHandoverGrantedMessage(
            org.mockito.ArgumentMatchers.eq(session),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.contains("deinen Fall übernommen"));
  }

  @Test
  void requestAccess_keepsContentLocked_WhenPolicyRequiresClientConsent() {
    givenIllnessRequiresClientConsent();
    CaseHandoverStatus status = requestAccess(123L, "COUNSELLOR_IS_ILL", "Need temporary cover.");

    assertEquals("PENDING_CLIENT_CONSENT", status.getStatus());
    assertFalse(status.isCanViewContent());
    assertTrue(status.isClientConsentRequired());
    assertEquals(previous, session.getConsultant());
    verify(sessionRepository, never()).save(session);
  }

  @Test
  void requestAccess_deniesAndKeepsContentLocked_WhenPolicyDoesNotAllowReason() {
    when(caseHandoverReasonPolicyRepository.findByEnabledTrueOrderByDisplayOrderAscCodeAsc())
        .thenReturn(
            List.of(reasonPolicy("OTHER_EMERGENCY", "Other emergency", false, false, true, 30)));

    CaseHandoverStatus status = requestAccess(123L, "OTHER_EMERGENCY", "Needs cover.");

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

    CaseHandoverStatus status = requestAccess(123L, "OTHER_EMERGENCY", "Needs cover.");

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
  void getStatus_keepsHistoricalGrantButLocksContent_WhenRequesterNoLongerOwnsSession() {
    Consultant successor = consultant("successor", "Successor Counsellor");
    session.setConsultant(successor);
    LocalDateTime createdAt = LocalDateTime.of(2026, 9, 12, 10, 15);
    LocalDateTime resolvedAt = LocalDateTime.of(2026, 9, 12, 10, 20);
    CaseHandoverRequest historicalGrant = grantedRequest(requester);
    historicalGrant.setCreatedAt(createdAt);
    historicalGrant.setResolvedAt(resolvedAt);
    when(caseHandoverRequestRepository.findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
            123L, "requester"))
        .thenReturn(List.of(historicalGrant));

    CaseHandoverStatus status = caseHandoverService.getStatus(123L);

    assertEquals(99L, status.getRequestId());
    assertEquals("GRANTED", status.getStatus());
    assertFalse(status.isCanViewContent());
    assertEquals("ACCESS_GRANTED", status.getAuditOutcome());
    assertEquals(createdAt, status.getCreatedAt());
    assertEquals(resolvedAt, status.getResolvedAt());
    assertEquals(successor, session.getConsultant());
    verify(caseHandoverRequestRepository, never()).save(any());
    verify(sessionRepository, never()).save(any());
  }

  @Test
  void getStatus_keepsHistoricalGrantLocked_WhenSessionHasNoOwner() {
    session.setConsultant(null);
    CaseHandoverRequest historicalGrant = grantedRequest(requester);
    when(caseHandoverRequestRepository.findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
            123L, "requester"))
        .thenReturn(List.of(historicalGrant));

    CaseHandoverStatus status = caseHandoverService.getStatus(123L);

    assertEquals(99L, status.getRequestId());
    assertEquals("GRANTED", status.getStatus());
    assertFalse(status.isCanViewContent());
    assertNull(session.getConsultant());
    verify(caseHandoverRequestRepository, never()).save(any());
    verify(sessionRepository, never()).save(any());
  }

  @Test
  void getStatus_keepsSyntheticGrantForCurrentOwnerWithoutHistoricalRequest() {
    session.setConsultant(requester);

    CaseHandoverStatus status = caseHandoverService.getStatus(123L);

    assertNull(status.getRequestId());
    assertEquals("GRANTED", status.getStatus());
    assertTrue(status.isCanViewContent());
    assertEquals("ACTIVE_OWNER", status.getAuditOutcome());
    verify(caseHandoverRequestRepository, never())
        .findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(any(), any());
    verify(caseHandoverRequestRepository, never()).save(any());
    verify(sessionRepository, never()).save(any());
  }

  @Test
  void requestAccess_returnsExistingHistoricalGrantLocked_WhenRequesterNoLongerOwnsSession() {
    Consultant successor = consultant("successor", "Successor Counsellor");
    session.setConsultant(successor);
    CaseHandoverRequest historicalGrant = grantedRequest(requester);
    when(caseHandoverRequestRepository.findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
            123L, "requester"))
        .thenReturn(List.of(historicalGrant));

    CaseHandoverStatus status = requestAccess(123L, "OTHER_EMERGENCY", "Needs cover again.");

    assertEquals(99L, status.getRequestId());
    assertEquals("GRANTED", status.getStatus());
    assertFalse(status.isCanViewContent());
    assertEquals("ACCESS_GRANTED", status.getAuditOutcome());
    assertEquals(successor, session.getConsultant());
    verify(caseHandoverRequestRepository, never()).save(any());
    verify(sessionRepository, never()).save(any());
  }

  @Test
  void searchCandidates_returnsMetadataOnlySameAgencyMatches() {
    when(sessionRepository.findByAgencyIdInAndConsultantNotAndStatusInOrderByUpdateDateDesc(
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
    when(sessionRepository.findByAgencyIdInAndConsultantNotAndStatusInOrderByUpdateDateDesc(
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
    when(sessionRepository.findByAgencyIdInAndConsultantNotAndStatusInOrderByUpdateDateDesc(
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

    requestAccess(123L, "COUNSELLOR_IS_ILL", "Illness cover.");

    verify(caseHandoverRequestRepository).save(captor.capture());
    CaseHandoverRequest saved = captor.getValue();
    assertEquals("COUNSELLOR_IS_ILL", saved.getReasonCode());
    assertEquals("Counsellor is ill", saved.getReasonLabel());
    assertEquals("Illness cover.", saved.getExplanation());
    assertEquals("ACCESS_GRANTED", saved.getAuditOutcome());
    assertEquals(previous, saved.getPreviousConsultant());
  }

  @Test
  void resolveClientConsent_activatesCounsellor_WhenClientApproves() {
    CaseHandoverRequest request = pendingConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));

    CaseHandoverStatus status = caseHandoverService.resolveClientConsent(123L, 88L, true);

    assertEquals("GRANTED", status.getStatus());
    assertTrue(status.isCanViewContent());
    assertNull(status.getReasonCode());
    assertNull(status.getReasonLabel());
    assertNull(status.getPolicyAuthority());
    assertEquals(requester, session.getConsultant());
    assertEquals(CaseHandoverRequest.Status.GRANTED, request.getStatus());
    assertEquals("ACCESS_GRANTED", request.getAuditOutcome());
    verify(sessionOwnershipService)
        .updateOwner(eq(session), eq(requester), eq(SessionStatus.IN_PROGRESS), any());
  }

  @Test
  void resolveClientConsent_revalidatesLiveRecipientBeforeFinalGrant() {
    CaseHandoverRequest request = pendingConsentRequest();
    requester.setAbsent(true);
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));
    when(consultantService.getConsultant("requester")).thenReturn(Optional.of(requester));

    assertThrows(
        ForbiddenException.class, () -> caseHandoverService.resolveClientConsent(123L, 88L, true));

    verify(sessionOwnershipService, never()).updateOwner(any(), any(), any(), any());
    verify(caseHandoverRequestRepository, never()).save(any());
  }

  @Test
  void resolveClientConsent_rejectsRecipientWhoseLiveTenantChanged() {
    CaseHandoverRequest request = pendingConsentRequest();
    requester.setTenantId(8L);
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));
    when(consultantService.getConsultant("requester")).thenReturn(Optional.of(requester));

    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException.class,
        () -> caseHandoverService.resolveClientConsent(123L, 88L, true));

    verify(sessionOwnershipService, never()).updateOwner(any(), any(), any(), any());
    verify(caseHandoverRequestRepository, never()).save(any());
  }

  @Test
  void resolveClientConsent_rejectsRecipientWhoseLiveAgencyChanged() {
    CaseHandoverRequest request = pendingConsentRequest();
    requester.getConsultantAgencies().iterator().next().setAgencyId(99L);
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));
    when(consultantService.getConsultant("requester")).thenReturn(Optional.of(requester));

    assertThrows(
        ForbiddenException.class, () -> caseHandoverService.resolveClientConsent(123L, 88L, true));

    verify(sessionOwnershipService, never()).updateOwner(any(), any(), any(), any());
    verify(caseHandoverRequestRepository, never()).save(any());
  }

  @Test
  void resolveClientConsent_rejectsRecipientWhoseLiveTopicChanged() {
    CaseHandoverRequest request = pendingConsentRequest();
    ReflectionTestUtils.setField(caseHandoverService, "topicsEnabled", true);
    givenRequesterTopics(99L);
    session.setMainTopicId(5L);
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));
    when(consultantService.getConsultant("requester")).thenReturn(Optional.of(requester));

    assertThrows(
        ForbiddenException.class, () -> caseHandoverService.resolveClientConsent(123L, 88L, true));

    verify(sessionOwnershipService, never()).updateOwner(any(), any(), any(), any());
    verify(caseHandoverRequestRepository, never()).save(any());
  }

  @Test
  void resolveClientConsent_legacyPendingAdviceFailsClosedWithoutGrant() {
    CaseHandoverRequest request = pendingConsentRequest();
    request.setReasonCode("COUNSELLOR_ASKED_FOR_ADVICE");
    request.setReasonLabel("Advice");
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));

    CaseHandoverStatus status = caseHandoverService.resolveClientConsent(123L, 88L, true);

    assertEquals("DENIED", status.getStatus());
    assertEquals(CaseHandoverRequest.Status.DENIED, request.getStatus());
    verify(sessionOwnershipService, never()).updateOwner(any(), any(), any(), any());
  }

  @Test
  void resolveClientConsent_legacyUnknownOwnershipPeriodFailsClosed() {
    CaseHandoverRequest request = pendingConsentRequest();
    request.setExpectedOwnershipRevision(null);
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));

    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class,
        () -> caseHandoverService.resolveClientConsent(123L, 88L, true));

    verify(sessionOwnershipService, never()).updateOwner(any(), any(), any(), any());
  }

  @Test
  void resolveClientConsent_keepsExistingGrantViewable_WhenRequesterStillOwnsSession() {
    session.setConsultant(requester);
    CaseHandoverRequest request = grantedRequest(requester);
    when(caseHandoverRequestRepository.findByIdAndSessionId(99L, 123L))
        .thenReturn(Optional.of(request));

    CaseHandoverStatus status = caseHandoverService.resolveClientConsent(123L, 99L, true);

    assertEquals(99L, status.getRequestId());
    assertEquals("GRANTED", status.getStatus());
    assertTrue(status.isCanViewContent());
    assertNull(status.getReasonCode());
    assertNull(status.getReasonLabel());
    assertNull(status.getPolicyAuthority());
    assertEquals(requester, session.getConsultant());
    verify(caseHandoverRequestRepository, never()).save(any());
    verify(sessionRepository, never()).save(any());
  }

  @Test
  void resolveClientConsent_keepsOldGrantButLocksContent_WhenRequesterNoLongerOwnsSession() {
    Consultant successor = consultant("successor", "Successor Counsellor");
    session.setConsultant(successor);
    CaseHandoverRequest request = grantedRequest(requester);
    when(caseHandoverRequestRepository.findByIdAndSessionId(99L, 123L))
        .thenReturn(Optional.of(request));

    CaseHandoverStatus status = caseHandoverService.resolveClientConsent(123L, 99L, false);

    assertEquals(99L, status.getRequestId());
    assertEquals("GRANTED", status.getStatus());
    assertFalse(status.isCanViewContent());
    assertEquals("ACCESS_GRANTED", status.getAuditOutcome());
    assertNull(status.getReasonCode());
    assertNull(status.getReasonLabel());
    assertNull(status.getPolicyAuthority());
    assertEquals(successor, session.getConsultant());
    verify(caseHandoverRequestRepository, never()).save(any());
    verify(sessionRepository, never()).save(any());
  }

  /**
   * A client-approved handover transfers ownership just as a granted requestAccess does, so the new
   * owner's standing supervisor has to attach on this path too. Without this test a regression on
   * the resolveClientConsent branch passes the whole suite.
   */
  @Test
  void resolveClientConsent_attachesTheNewOwnersStandingSupervisor_WhenClientApproves() {
    CaseHandoverRequest request = pendingConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));

    caseHandoverService.resolveClientConsent(123L, 88L, true);

    verify(sessionSupervisorFacade).attachStandingSupervisorIfAssigned(123L, requester);
  }

  /**
   * The production path is transactional, so it takes the deferred branch, not the
   * no-synchronization fallback the test above exercises. Assert the real one: nothing before the
   * commit, then the REQUIRES_NEW entry point and never the plain method.
   */
  @Test
  void resolveClientConsent_defersTheSupervisorAttachToANewTransaction_WhenClientApproves() {
    CaseHandoverRequest request = pendingConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));
    TransactionSynchronizationManager.initSynchronization();
    try {
      caseHandoverService.resolveClientConsent(123L, 88L, true);

      verify(sessionSupervisorFacade, never())
          .attachStandingSupervisorInNewTransaction(any(), any());

      TransactionSynchronizationManager.getSynchronizations()
          .forEach(synchronization -> synchronization.afterCommit());

      verify(sessionSupervisorFacade).attachStandingSupervisorInNewTransaction(123L, requester);
      verify(sessionSupervisorFacade, never()).attachStandingSupervisorIfAssigned(any(), any());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  /**
   * The facade swallows its own failures, but the REQUIRES_NEW commit happens after that catch
   * returns, so a rollback-only transaction throws out of the proxy. The handover has already
   * committed by then; letting it escape would 500 a successful takeover.
   */
  @Test
  void requestAccess_swallowsASupervisorAttachFailureRaisedByTheNewTransactionsCommit() {
    doThrow(new UnexpectedRollbackException("transaction rolled back"))
        .when(sessionSupervisorFacade)
        .attachStandingSupervisorInNewTransaction(any(), any());
    TransactionSynchronizationManager.initSynchronization();
    try {
      requestAccess(123L, "OTHER_EMERGENCY", "Colleague is unavailable.");

      TransactionSynchronizationManager.getSynchronizations()
          .forEach(synchronization -> synchronization.afterCommit());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    verify(sessionSupervisorFacade).attachStandingSupervisorInNewTransaction(123L, requester);
  }

  @Test
  void resolveClientConsent_doesNotAttachAStandingSupervisor_WhenClientDeclines() {
    CaseHandoverRequest request = pendingConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));

    caseHandoverService.resolveClientConsent(123L, 88L, false);

    verify(sessionSupervisorFacade, never()).attachStandingSupervisorIfAssigned(any(), any());
  }

  @Test
  void resolveClientConsent_describesOwnershipTransferInsteadOfTemporarySupervision() {
    CaseHandoverRequest request = pendingConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));
    ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);

    caseHandoverService.resolveClientConsent(123L, 88L, true);

    verify(matrixSessionSystemMessageService)
        .postCaseHandoverGrantedMessage(
            org.mockito.ArgumentMatchers.eq(session),
            org.mockito.ArgumentMatchers.eq("Requesting Counsellor"),
            description.capture());
    assertTrue(description.getValue().contains("hat deinen Fall übernommen"));
    assertFalse(description.getValue().contains("zeitweise mitlesen"));
    assertFalse(description.getValue().contains("bleibt für dich zuständig"));
  }

  @Test
  void resolveClientConsent_keepsContentLocked_WhenClientDeclines() {
    CaseHandoverRequest request = pendingConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));

    CaseHandoverStatus status = caseHandoverService.resolveClientConsent(123L, 88L, false);

    assertEquals("CLIENT_CONSENT_DECLINED", status.getStatus());
    assertFalse(status.isCanViewContent());
    assertNull(status.getReasonCode());
    assertNull(status.getReasonLabel());
    assertNull(status.getPolicyAuthority());
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
    assertNull(status.getReasonCode());
    assertNull(status.getReasonLabel());
    assertNull(status.getPolicyAuthority());
    assertEquals(other, session.getConsultant());
    assertEquals(CaseHandoverRequest.Status.DENIED, request.getStatus());
    assertEquals("ALREADY_ANSWERED", request.getAuditOutcome());
    verify(sessionRepository, never()).save(session);
  }

  // --- #202: scope handover candidate search to the requester's departments (agency x topic) ---

  private void givenRequesterTopics(Long... topicIds) {
    Set<ConsultantTopic> topics = new HashSet<>();
    for (Long topicId : topicIds) {
      ConsultantTopic topic = new ConsultantTopic();
      topic.setConsultant(requester);
      topic.setTopicId(topicId);
      topics.add(topic);
    }
    requester.setConsultantTopics(topics);
  }

  private Session candidateSession(long id, long agencyId, Long mainTopicId, boolean teamSession) {
    Session candidate = new Session();
    candidate.setId(id);
    candidate.setAgencyId(agencyId);
    candidate.setConsultant(previous);
    candidate.setUser(asker);
    candidate.setStatus(SessionStatus.IN_PROGRESS);
    candidate.setRegistrationType(Session.RegistrationType.REGISTERED);
    candidate.setTenantId(7L);
    candidate.setPostcode("12345");
    candidate.setLanguageCode(LanguageCode.de);
    candidate.setMainTopicId(mainTopicId);
    candidate.setTeamSession(teamSession);
    candidate.setCreateDate(LocalDateTime.now());
    candidate.setUpdateDate(LocalDateTime.now());
    return candidate;
  }

  private void givenCandidates(Session... candidates) {
    when(sessionRepository.findByAgencyIdInAndConsultantNotAndStatusInOrderByUpdateDateDesc(
            List.of(10L), requester, List.of(SessionStatus.IN_PROGRESS, SessionStatus.DONE)))
        .thenReturn(List.of(candidates));
  }

  @Test
  void searchCandidates_includesTeamSessions() {
    // The silent-membership handover model is team-session based, so the old TeamSessionFalse
    // restriction hid exactly the cases the feature exists for (#202).
    givenRequesterTopics(5L);
    givenCandidates(candidateSession(201L, 10L, 5L, true));

    var response = caseHandoverService.searchCandidates("asker", 0, 15, false);

    assertEquals(1, response.getTotal());
    assertEquals(201L, response.getSessions().get(0).getSession().getId());
  }

  @Test
  void searchCandidates_returnsSessionsOfTheRequesterDepartment() {
    givenRequesterTopics(5L);
    givenCandidates(candidateSession(202L, 10L, 5L, false));

    var response = caseHandoverService.searchCandidates("asker", 0, 15, false);

    assertEquals(1, response.getTotal());
    assertEquals(202L, response.getSessions().get(0).getSession().getId());
  }

  @Test
  void searchCandidates_excludesSameAgencySessionsOfAnotherDepartment() {
    // Same agency, different topic: a different department, so out of scope even though the
    // repository query returns it.
    givenRequesterTopics(5L);
    givenCandidates(candidateSession(203L, 10L, 99L, false));

    var response = caseHandoverService.searchCandidates("asker", 0, 15, false);

    assertEquals(0, response.getTotal());
    assertTrue(response.getSessions().isEmpty());
  }

  @Test
  void searchCandidates_excludesSessionsWithoutTopicWhenRequesterHasDepartments() {
    givenRequesterTopics(5L);
    givenCandidates(candidateSession(204L, 10L, null, false));

    var response = caseHandoverService.searchCandidates("asker", 0, 15, false);

    assertEquals(0, response.getTotal());
  }

  @Test
  void searchCandidates_unionsAcrossAllRequesterDepartments() {
    givenRequesterTopics(5L, 6L);
    givenCandidates(
        candidateSession(205L, 10L, 5L, false),
        candidateSession(206L, 10L, 6L, true),
        candidateSession(207L, 10L, 99L, false));

    var response = caseHandoverService.searchCandidates("asker", 0, 15, false);

    assertEquals(2, response.getTotal());
    assertEquals(
        List.of(205L, 206L),
        response.getSessions().stream().map(dto -> dto.getSession().getId()).toList());
  }

  @Test
  void searchCandidates_matchesOnAnySessionTopicNotOnlyTheMainTopic() {
    // A session carrying several topics belongs to a department per topic, so it stays reachable
    // from any of them.
    givenRequesterTopics(6L);
    Session multiTopic = candidateSession(208L, 10L, 5L, false);
    SessionTopic secondary = new SessionTopic();
    secondary.setSession(multiTopic);
    secondary.setTopicId(6L);
    multiTopic.setSessionTopics(List.of(secondary));
    givenCandidates(multiTopic);

    var response = caseHandoverService.searchCandidates("asker", 0, 15, false);

    assertEquals(1, response.getTotal());
    assertEquals(208L, response.getSessions().get(0).getSession().getId());
  }

  @Test
  void searchCandidates_keepsAgencyWideViewWhenRequesterHasNoTopics() {
    // Deployments without topics (feature.topics.enabled=false) have no department dimension, so
    // the scope degenerates to the agency rather than emptying the feature.
    requester.setConsultantTopics(new HashSet<>());
    givenCandidates(
        candidateSession(209L, 10L, null, false), candidateSession(210L, 10L, 5L, true));

    var response = caseHandoverService.searchCandidates("asker", 0, 15, false);

    assertEquals(2, response.getTotal());
  }

  @Test
  void searchCandidates_returnsNothingWhenTopicsEnabledAndRequesterHasNoTopics() {
    ReflectionTestUtils.setField(caseHandoverService, "topicsEnabled", true);
    requester.setConsultantTopics(new HashSet<>());
    givenCandidates(candidateSession(211L, 10L, 5L, true));

    var response = caseHandoverService.searchCandidates("asker", 0, 15, false);

    assertEquals(0, response.getTotal());
  }

  @Test
  void searchCandidates_excludesOtherAgencyEvenWhenRepositoryReturnsIt() {
    givenRequesterTopics(5L);
    givenCandidates(candidateSession(212L, 99L, 5L, true));

    var response = caseHandoverService.searchCandidates("asker", 0, 15, false);

    assertEquals(0, response.getTotal());
  }

  @Test
  void searchCandidates_unionsDepartmentsAcrossAgencies() {
    ConsultantAgency firstAgency = requester.getConsultantAgencies().iterator().next();
    firstAgency.setId(1L);
    ConsultantAgency secondAgency = new ConsultantAgency();
    secondAgency.setId(2L);
    secondAgency.setAgencyId(20L);
    secondAgency.setConsultant(requester);
    requester.setConsultantAgencies(Set.of(firstAgency, secondAgency));
    givenRequesterTopics(5L, 6L);
    when(sessionRepository.findByAgencyIdInAndConsultantNotAndStatusInOrderByUpdateDateDesc(
            any(), eq(requester), eq(List.of(SessionStatus.IN_PROGRESS, SessionStatus.DONE))))
        .thenReturn(
            List.of(
                candidateSession(213L, 10L, 5L, false),
                candidateSession(214L, 20L, 6L, true),
                candidateSession(215L, 20L, 99L, false)));

    var response = caseHandoverService.searchCandidates("asker", 0, 15, false);

    assertEquals(2, response.getTotal());
    assertEquals(
        List.of(213L, 214L),
        response.getSessions().stream().map(dto -> dto.getSession().getId()).toList());
  }

  @Test
  void requestAccess_forbidsASessionOutsideTheRequesterDepartment() {
    givenRequesterTopics(5L);
    session.setMainTopicId(99L);

    assertThrows(
        ForbiddenException.class, () -> requestAccess(123L, "COUNSELLOR_IS_ILL", "Cover."));
  }

  @Test
  void getStatus_forbidsASessionOutsideTheRequesterDepartment() {
    givenRequesterTopics(5L);
    session.setMainTopicId(99L);

    assertThrows(ForbiddenException.class, () -> caseHandoverService.getStatus(123L));
  }

  private UUID prepareDeliverableHandoverMail() throws Exception {
    UUID requesterId = assignValidRequesterId();
    when(consultantService.getConsultant(requesterId.toString()))
        .thenReturn(Optional.of(requester));
    session.setMatrixRoomId("!handover-room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    previous.setMatrixUserId("@previous:matrix");
    when(matrixSynapseService.loginAsUserAccessToken("@previous:matrix"))
        .thenReturn("previous-token");
    when(matrixSynapseService.loginAsUserAccessToken("@requester:matrix"))
        .thenReturn("requester-token");
    when(matrixSynapseService.joinRoom("!handover-room:matrix", "requester-token"))
        .thenReturn(true);
    return requesterId;
  }

  private UUID assignValidRequesterId() {
    UUID requesterId = UUID.randomUUID();
    requester.setId(requesterId.toString());
    when(caseHandoverRequestRepository.findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
            123L, requesterId.toString()))
        .thenReturn(List.of());
    return requesterId;
  }

  private void assignPersistedRequestId(Long requestId) {
    when(caseHandoverRequestRepository.save(any(CaseHandoverRequest.class)))
        .thenAnswer(
            invocation -> {
              CaseHandoverRequest saved = invocation.getArgument(0);
              saved.setId(requestId);
              return saved;
            });
  }

  private CaseHandoverStatus requestAccess(Long sessionId, String reasonCode, String explanation) {
    return caseHandoverService.requestAccess(
        sessionId, reasonCode, explanation, session.getOwnershipRevision(), UUID.randomUUID());
  }

  private CaseHandoverStatus requestAccess(
      Long sessionId,
      String reasonCode,
      String explanation,
      Long expectedOwnershipRevision,
      UUID operationId) {
    return caseHandoverService.requestAccess(
        sessionId, reasonCode, explanation, expectedOwnershipRevision, operationId);
  }

  private Consultant consultant(String id, String displayName) {
    Consultant consultant = new Consultant();
    consultant.setId(id);
    consultant.setTenantId(7L);
    consultant.setUsername(id);
    consultant.setFirstName(id);
    consultant.setLastName("User");
    consultant.setEmail(id + "@example.org");
    consultant.setDisplayName(displayName);
    return consultant;
  }

  private Consultant eligibleConsultant(String id, String displayName) {
    Consultant consultant = consultant(id, displayName);
    ConsultantAgency agency = new ConsultantAgency();
    agency.setAgencyId(10L);
    agency.setConsultant(consultant);
    consultant.setConsultantAgencies(Set.of(agency));
    return consultant;
  }

  private CaseHandoverRequest pushRequest(Consultant recipient, UUID operationId) {
    return CaseHandoverRequest.builder()
        .id(501L)
        .session(session)
        .requesterConsultant(recipient)
        .initiatorConsultant(requester)
        .previousConsultant(requester)
        .direction(CaseHandoverRequest.Direction.PUSH)
        .expectedOwnershipRevision(0L)
        .operationId(operationId)
        .reasonCode("OTHER_EMERGENCY")
        .reasonLabel("Other emergency")
        .explanation("")
        .status(CaseHandoverRequest.Status.PENDING_RECIPIENT_ACCEPTANCE)
        .clientConsentRequired(false)
        .policyAuthority("platform-admin-default-case-handover-policy")
        .auditOutcome("PENDING_RECIPIENT_ACCEPTANCE")
        .tenantId(7L)
        .build();
  }

  private CaseHandoverRequest pendingConsentRequest() {
    return CaseHandoverRequest.builder()
        .id(88L)
        .session(session)
        .requesterConsultant(requester)
        .initiatorConsultant(requester)
        .previousConsultant(previous)
        .direction(CaseHandoverRequest.Direction.PULL)
        .expectedOwnershipRevision(0L)
        .operationId(UUID.fromString("c4ce791f-54b5-4180-8c31-b4f13b20ec03"))
        .reasonCode("COUNSELLOR_IS_ILL")
        .reasonLabel("Counsellor is ill")
        .explanation("Need a second opinion.")
        .status(CaseHandoverRequest.Status.PENDING_CLIENT_CONSENT)
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
        .initiatorConsultant(consultant)
        .previousConsultant(previous)
        .direction(CaseHandoverRequest.Direction.PULL)
        .expectedOwnershipRevision(0L)
        .operationId(UUID.fromString("b975d19f-43b8-44eb-b845-3992e57ab06e"))
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

  private void givenIllnessRequiresClientConsent() {
    when(caseHandoverReasonPolicyRepository.findByEnabledTrueOrderByDisplayOrderAscCodeAsc())
        .thenReturn(
            List.of(reasonPolicy("COUNSELLOR_IS_ILL", "Counsellor is ill", true, true, true, 40)));
  }
}
