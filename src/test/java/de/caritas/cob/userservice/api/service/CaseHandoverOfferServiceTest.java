package de.caritas.cob.userservice.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.facade.SessionSupervisorFacade;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest.Direction;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest.Status;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CaseHandoverReasonPolicyRepository;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.CaseHandoverService.CaseHandoverOffer;
import de.caritas.cob.userservice.api.service.CaseHandoverService.CaseHandoverStatus;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The PUSH direction: an owner offers their own case, a named colleague accepts or declines.
 *
 * <p>The scenarios that matter are the ones where a case could get lost or move without agreement —
 * offering someone else's case, a second offer on top of an open one, accepting an offer meant for
 * somebody else, and an offer nobody answers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CaseHandoverOfferServiceTest {

  @InjectMocks private CaseHandoverService caseHandoverService;

  @Mock private CaseHandoverRequestRepository caseHandoverRequestRepository;
  @Mock private CaseHandoverReasonPolicyRepository caseHandoverReasonPolicyRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private UserAccountService userAccountService;
  @Mock private EventNotificationService eventNotificationService;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private MatrixSessionSystemMessageService matrixSessionSystemMessageService;
  @Mock private SessionSupervisorFacade sessionSupervisorFacade;
  @Mock private CaseHandoverPolicyCacheService caseHandoverPolicyCacheService;

  @Mock
  private de.caritas.cob.userservice.api.service.notification.CaseHandoverEmailNotification
      caseHandoverEmailNotification;

  @Mock
  private de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService
      scheduledTaskClaimService;

  @org.mockito.Spy private java.time.Clock clock = java.time.Clock.systemDefaultZone();

  private Consultant owner;
  private Consultant colleague;
  private Consultant stranger;
  private Session session;

  @BeforeEach
  void setUp() {
    owner = consultant("owner", 10L);
    colleague = consultant("00000000-0000-0000-0000-000000000002", 10L);
    stranger = consultant("stranger", 99L);

    User asker = new User();
    asker.setUserId("asker");
    asker.setUsername("asker");

    session = new Session();
    session.setId(123L);
    session.setAgencyId(10L);
    session.setConsultant(owner);
    session.setUser(asker);
    session.setStatus(SessionStatus.IN_PROGRESS);
    session.setRegistrationType(Session.RegistrationType.REGISTERED);
    session.setTenantId(7L);
    session.setLanguageCode(LanguageCode.de);
    session.setCreateDate(LocalDateTime.now());
    session.setUpdateDate(LocalDateTime.now());

    ReflectionTestUtils.setField(
        caseHandoverService, "offerValidity", java.time.Duration.ofHours(72));

    when(userAccountService.retrieveValidatedConsultant()).thenReturn(owner);
    when(sessionRepository.findById(123L)).thenReturn(Optional.of(session));
    when(consultantRepository.findByIdAndDeleteDateIsNull("00000000-0000-0000-0000-000000000002"))
        .thenReturn(Optional.of(colleague));
    when(consultantRepository.findByIdAndDeleteDateIsNull("stranger"))
        .thenReturn(Optional.of(stranger));
    when(consultantRepository.findByIdAndDeleteDateIsNull("owner")).thenReturn(Optional.of(owner));
    when(caseHandoverReasonPolicyRepository.findByEnabledTrueOrderByDisplayOrderAscCodeAsc())
        .thenReturn(List.of());
    when(caseHandoverRequestRepository.findBySessionIdAndDirectionAndStatus(
            123L, Direction.PUSH, Status.PENDING_RECIPIENT_ACCEPT))
        .thenReturn(List.of());
    when(caseHandoverRequestRepository.findBySessionIdAndStatusOrderByCreatedAtDesc(
            123L, Status.GRANTED))
        .thenReturn(List.of());
    when(caseHandoverRequestRepository.save(any(CaseHandoverRequest.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void createOffer_storesAPendingPushOfferWithoutMovingTheCase() {
    CaseHandoverOffer offer =
        caseHandoverService.createOffer(
            123L, "00000000-0000-0000-0000-000000000002", "PLANNED_ABSENCE", "Two weeks leave.");

    ArgumentCaptor<CaseHandoverRequest> captor = ArgumentCaptor.forClass(CaseHandoverRequest.class);
    verify(caseHandoverRequestRepository).save(captor.capture());
    CaseHandoverRequest saved = captor.getValue();

    assertEquals(Direction.PUSH, saved.getDirection());
    assertEquals(Status.PENDING_RECIPIENT_ACCEPT, saved.getStatus());
    assertEquals(colleague, saved.getTargetConsultant());
    // The target is also the requester: accepting makes them the owner and the shared grant path
    // reads the requester.
    assertEquals(colleague, saved.getRequesterConsultant());
    assertEquals(owner, saved.getPreviousConsultant());
    assertNotNull(saved.getOfferExpiresAt());
    assertEquals(owner, session.getConsultant(), "the case must not move before acceptance");
    verify(sessionRepository, never()).save(any());
    assertEquals("PENDING_RECIPIENT_ACCEPT", offer.getStatus());
  }

  @Test
  void createOffer_notifiesTheColleagueTheCaseIsOfferedTo() {
    caseHandoverService.createOffer(
        123L, "00000000-0000-0000-0000-000000000002", "PLANNED_ABSENCE", "Two weeks leave.");

    verify(eventNotificationService)
        .createEvent(
            eq("00000000-0000-0000-0000-000000000002"),
            eq("case.handover.offered"),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any(),
            eq(123L),
            eq(7L));
  }

  @Test
  void createOffer_isRejectedWhenIAmNotTheActiveCounsellor() {
    session.setConsultant(colleague);

    assertThrows(
        ForbiddenException.class,
        () ->
            caseHandoverService.createOffer(
                123L, "00000000-0000-0000-0000-000000000002", "PLANNED_ABSENCE", "Leave."));
  }

  @Test
  void createOffer_isRejectedWhenTheColleagueWorksInAnotherCounsellingCentre() {
    assertThrows(
        ForbiddenException.class,
        () -> caseHandoverService.createOffer(123L, "stranger", "PLANNED_ABSENCE", "Leave."));
  }

  @Test
  void createOffer_isRejectedWhenOfferedToMyself() {
    assertThrows(
        BadRequestException.class,
        () -> caseHandoverService.createOffer(123L, "owner", "PLANNED_ABSENCE", "Leave."));
  }

  @Test
  void createOffer_isRejectedForAnUnknownConsultant() {
    assertThrows(
        NotFoundException.class,
        () -> caseHandoverService.createOffer(123L, "ghost", "PLANNED_ABSENCE", "Leave."));
  }

  @Test
  void createOffer_isRejectedWhileAnotherOfferIsStillOpen() {
    when(caseHandoverRequestRepository.findBySessionIdAndDirectionAndStatus(
            123L, Direction.PUSH, Status.PENDING_RECIPIENT_ACCEPT))
        .thenReturn(
            List.of(offer(Status.PENDING_RECIPIENT_ACCEPT, LocalDateTime.now().plusDays(1))));

    assertThrows(
        ConflictException.class,
        () ->
            caseHandoverService.createOffer(
                123L, "00000000-0000-0000-0000-000000000002", "PLANNED_ABSENCE", "Leave."));
  }

  /** A stale offer past its window must not block the case until the sweep happens to run. */
  @Test
  void createOffer_ignoresAnOfferThatHasAlreadyRunOut() {
    when(caseHandoverRequestRepository.findBySessionIdAndDirectionAndStatus(
            123L, Direction.PUSH, Status.PENDING_RECIPIENT_ACCEPT))
        .thenReturn(
            List.of(offer(Status.PENDING_RECIPIENT_ACCEPT, LocalDateTime.now().minusHours(1))));

    CaseHandoverOffer created =
        caseHandoverService.createOffer(
            123L, "00000000-0000-0000-0000-000000000002", "PLANNED_ABSENCE", "Leave.");

    assertEquals("PENDING_RECIPIENT_ACCEPT", created.getStatus());
  }

  @Test
  void acceptOffer_runsTheSameGrantPathAsAPullRequest() {
    CaseHandoverRequest open =
        offer(Status.PENDING_RECIPIENT_ACCEPT, LocalDateTime.now().plusDays(1));
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(colleague);
    when(caseHandoverRequestRepository.findById(55L)).thenReturn(Optional.of(open));

    CaseHandoverStatus status = caseHandoverService.acceptOffer(55L);

    assertEquals("GRANTED", status.getStatus());
    assertTrue(status.isCanViewContent());
    assertEquals(colleague, session.getConsultant());
    verify(sessionRepository).save(session);
    verify(sessionSupervisorFacade).attachStandingSupervisorIfAssigned(123L, colleague);
    verify(matrixSessionSystemMessageService)
        .postCaseHandoverGrantedMessage(eq(session), anyString(), anyString());
  }

  @Test
  void acceptOffer_tellsTheOfferingCounsellorTheOfferWasAccepted() {
    CaseHandoverRequest open =
        offer(Status.PENDING_RECIPIENT_ACCEPT, LocalDateTime.now().plusDays(1));
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(colleague);
    when(caseHandoverRequestRepository.findById(55L)).thenReturn(Optional.of(open));

    caseHandoverService.acceptOffer(55L);

    verify(eventNotificationService)
        .createEvent(
            eq("owner"),
            eq("case.handover.accepted"),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any(),
            eq(123L),
            eq(7L));
  }

  /**
   * ADR-022 allows exactly two consent gates. A push must not add a third: the advice seeker is
   * asked when the reason policy says so, and then the case waits — it does not move first.
   */
  @Test
  void acceptOffer_waitsForTheClientWhenTheReasonPolicyRequiresConsent() {
    CaseHandoverRequest open =
        offer(Status.PENDING_RECIPIENT_ACCEPT, LocalDateTime.now().plusDays(1));
    open.setClientConsentRequired(true);
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(colleague);
    when(caseHandoverRequestRepository.findById(55L)).thenReturn(Optional.of(open));

    CaseHandoverStatus status = caseHandoverService.acceptOffer(55L);

    assertEquals("PENDING_CLIENT_CONSENT", status.getStatus());
    assertFalse(status.isCanViewContent());
    assertEquals(owner, session.getConsultant(), "the case stays put until the client agrees");
    verify(sessionRepository, never()).save(any());
    verify(eventNotificationService)
        .createEvent(
            eq("asker"),
            eq("case.handover.consent.requested"),
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyString(),
            eq(123L),
            eq(7L));
  }

  @Test
  void acceptAdviceOfferKeepsOwnerAndFrozenDurationWithoutTakeoverMail() {
    CaseHandoverRequest open =
        offer(Status.PENDING_RECIPIENT_ACCEPT, LocalDateTime.now().plusDays(1));
    open.setReasonCode("ADVICE_REQUESTED");
    open.setAccessType(CaseHandoverRequest.AccessType.CO_ACCESS);
    open.setMaxAccessDurationMinutes(75);
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(colleague);
    when(caseHandoverRequestRepository.findById(55L)).thenReturn(Optional.of(open));
    var status = caseHandoverService.acceptOffer(55L);
    assertEquals(owner, session.getConsultant());
    assertEquals("CO_ACCESS", status.getAccessType());
    assertEquals(open.getResolvedAt().plusMinutes(75), open.getExpiresAt());
    verify(sessionRepository, never()).save(any());
    verify(caseHandoverEmailNotification, never())
        .ownershipGranted(any(), any(), any(), any(), any());
    verify(caseHandoverEmailNotification, never()).takeoverConsentRequested(any(), any(), any());
  }

  @Test
  void acceptedTakeoverSchedulesExistingMailWithPersistedTenantAndNoExplanation() {
    CaseHandoverRequest open =
        offer(Status.PENDING_RECIPIENT_ACCEPT, LocalDateTime.now().plusDays(1));
    open.setExplanation("Private staff detail");
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(colleague);
    when(caseHandoverRequestRepository.findById(55L)).thenReturn(Optional.of(open));
    caseHandoverService.acceptOffer(55L);
    verify(caseHandoverEmailNotification)
        .ownershipGranted(
            eq(55L),
            any(),
            eq(java.util.UUID.fromString(colleague.getId())),
            eq("owner"),
            eq(new de.caritas.cob.userservice.api.tenant.TenantData(7L, null)));
  }

  @Test
  void acceptedTakeoverWithConsentSchedulesConsentMailButNoOwnershipMail() {
    CaseHandoverRequest open =
        offer(Status.PENDING_RECIPIENT_ACCEPT, LocalDateTime.now().plusDays(1));
    open.setClientConsentRequired(true);
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(colleague);
    when(caseHandoverRequestRepository.findById(55L)).thenReturn(Optional.of(open));
    caseHandoverService.acceptOffer(55L);
    verify(caseHandoverEmailNotification)
        .takeoverConsentRequested(
            eq(55L), any(), eq(new de.caritas.cob.userservice.api.tenant.TenantData(7L, null)));
    verify(caseHandoverEmailNotification, never())
        .ownershipGranted(any(), any(), any(), any(), any());
  }

  @Test
  void acceptOffer_isRejectedWhenTheOfferWasMadeToSomebodyElse() {
    CaseHandoverRequest open =
        offer(Status.PENDING_RECIPIENT_ACCEPT, LocalDateTime.now().plusDays(1));
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(stranger);
    when(caseHandoverRequestRepository.findById(55L)).thenReturn(Optional.of(open));

    assertThrows(ForbiddenException.class, () -> caseHandoverService.acceptOffer(55L));
    assertEquals(owner, session.getConsultant());
  }

  @Test
  void acceptOffer_isRejectedWhenTheOfferWasAlreadyAnswered() {
    CaseHandoverRequest declined =
        offer(Status.RECIPIENT_DECLINED, LocalDateTime.now().plusDays(1));
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(colleague);
    when(caseHandoverRequestRepository.findById(55L)).thenReturn(Optional.of(declined));

    assertThrows(ConflictException.class, () -> caseHandoverService.acceptOffer(55L));
  }

  @Test
  void acceptOffer_closesAndRejectsAnOfferPastItsWindow() {
    CaseHandoverRequest stale =
        offer(Status.PENDING_RECIPIENT_ACCEPT, LocalDateTime.now().minusMinutes(1));
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(colleague);
    when(caseHandoverRequestRepository.findById(55L)).thenReturn(Optional.of(stale));

    assertThrows(ConflictException.class, () -> caseHandoverService.acceptOffer(55L));
    assertEquals(Status.EXPIRED, stale.getStatus());
    assertEquals(owner, session.getConsultant());
  }

  @Test
  void acceptOffer_isRejectedForAPullRequestId() {
    CaseHandoverRequest pull =
        offer(Status.PENDING_RECIPIENT_ACCEPT, LocalDateTime.now().plusDays(1));
    pull.setDirection(Direction.PULL);
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(colleague);
    when(caseHandoverRequestRepository.findById(55L)).thenReturn(Optional.of(pull));

    assertThrows(NotFoundException.class, () -> caseHandoverService.acceptOffer(55L));
  }

  @Test
  void declineOffer_leavesTheCaseWithTheOfferingCounsellorAndTellsThem() {
    CaseHandoverRequest open =
        offer(Status.PENDING_RECIPIENT_ACCEPT, LocalDateTime.now().plusDays(1));
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(colleague);
    when(caseHandoverRequestRepository.findById(55L)).thenReturn(Optional.of(open));

    CaseHandoverOffer declined = caseHandoverService.declineOffer(55L);

    assertEquals("RECIPIENT_DECLINED", declined.getStatus());
    assertEquals(owner, session.getConsultant());
    verify(sessionRepository, never()).save(any());
    verify(eventNotificationService)
        .createEvent(
            eq("owner"),
            eq("case.handover.declined"),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any(),
            eq(123L),
            eq(7L));
  }

  @Test
  void withdrawOffer_isOnlyAllowedForTheCounsellorWhoMadeIt() {
    CaseHandoverRequest open =
        offer(Status.PENDING_RECIPIENT_ACCEPT, LocalDateTime.now().plusDays(1));
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(colleague);
    when(caseHandoverRequestRepository.findById(55L)).thenReturn(Optional.of(open));

    assertThrows(ForbiddenException.class, () -> caseHandoverService.withdrawOffer(55L));

    when(userAccountService.retrieveValidatedConsultant()).thenReturn(owner);
    assertEquals("WITHDRAWN", caseHandoverService.withdrawOffer(55L).getStatus());
  }

  @Test
  void expireOffers_closesUnansweredOffersAndTellsTheOfferingCounsellor() {
    CaseHandoverRequest stale =
        offer(Status.PENDING_RECIPIENT_ACCEPT, LocalDateTime.now().minusHours(1));
    when(caseHandoverRequestRepository.findByStatusAndOfferExpiresAtBefore(
            eq(Status.PENDING_RECIPIENT_ACCEPT), any(LocalDateTime.class)))
        .thenReturn(List.of(stale));

    assertEquals(1, caseHandoverService.expireOffers());

    assertEquals(Status.EXPIRED, stale.getStatus());
    assertEquals("OFFER_EXPIRED", stale.getAuditOutcome());
    verify(eventNotificationService)
        .createEvent(
            eq("owner"),
            eq("case.handover.expired"),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any(),
            eq(123L),
            eq(7L));
  }

  @Test
  void listOffers_hidesOffersThatHaveAlreadyRunOut() {
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(colleague);
    when(caseHandoverRequestRepository.findByTargetConsultantIdAndStatusOrderByCreatedAtDesc(
            "00000000-0000-0000-0000-000000000002", Status.PENDING_RECIPIENT_ACCEPT))
        .thenReturn(
            List.of(
                offer(Status.PENDING_RECIPIENT_ACCEPT, LocalDateTime.now().plusDays(1)),
                offer(Status.PENDING_RECIPIENT_ACCEPT, LocalDateTime.now().minusDays(1))));

    assertEquals(1, caseHandoverService.listOffers(true).size());
  }

  /**
   * The colleague picker is scoped to the AGENCY, not to the offering counsellor's department: in a
   * team agency the department filter hides exactly the people a case would be handed to (FE#1152).
   */
  @Test
  void listColleagues_returnsTheAgencyMinusMe() {
    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNullOrderByConsultantFirstNameAsc(
            10L))
        .thenReturn(List.of(agencyLink(owner, 10L), agencyLink(colleague, 10L)));

    var result = caseHandoverService.listColleagues(123L, "", 0, 25);

    assertEquals(1, result.getTotal());
    assertEquals(
        "00000000-0000-0000-0000-000000000002", result.getColleagues().get(0).getConsultantId());
  }

  @Test
  void listColleagues_isRejectedWhenIAmNotTheActiveCounsellor() {
    session.setConsultant(colleague);

    assertThrows(
        ForbiddenException.class, () -> caseHandoverService.listColleagues(123L, "", 0, 25));
  }

  private CaseHandoverRequest offer(Status status, LocalDateTime expiresAt) {
    return CaseHandoverRequest.builder()
        .id(55L)
        .session(session)
        .direction(Direction.PUSH)
        .requesterConsultant(colleague)
        .targetConsultant(colleague)
        .previousConsultant(owner)
        .reasonCode("PLANNED_ABSENCE")
        .reasonLabel("Planned absence")
        .explanation("Two weeks leave.")
        .status(status)
        .clientConsentRequired(false)
        .policyAuthority("platform-admin-default-case-handover-policy")
        .auditOutcome("OFFER_PENDING_RECIPIENT")
        .createdAt(LocalDateTime.now().minusHours(1))
        .offerExpiresAt(expiresAt)
        .tenantId(7L)
        .build();
  }

  private Consultant consultant(String id, Long agencyId) {
    Consultant consultant = new Consultant();
    consultant.setId(id);
    consultant.setUsername(id);
    consultant.setFirstName(id);
    consultant.setLastName("User");
    consultant.setEmail(id + "@example.org");
    consultant.setDisplayName(id);
    consultant.setConsultantAgencies(Set.of(agencyLink(consultant, agencyId)));
    return consultant;
  }

  private ConsultantAgency agencyLink(Consultant consultant, Long agencyId) {
    ConsultantAgency consultantAgency = new ConsultantAgency();
    consultantAgency.setAgencyId(agencyId);
    consultantAgency.setConsultant(consultant);
    return consultantAgency;
  }
}
