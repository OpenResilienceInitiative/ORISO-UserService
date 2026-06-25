package de.caritas.cob.userservice.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
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
import de.caritas.cob.userservice.api.service.CaseHandoverService.CaseHandoverStatus;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CaseHandoverServiceTest {

  @InjectMocks private CaseHandoverService caseHandoverService;

  @Mock private CaseHandoverRequestRepository caseHandoverRequestRepository;
  @Mock private CaseHandoverReasonPolicyRepository caseHandoverReasonPolicyRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private UserAccountService userAccountService;
  @Mock private EventNotificationService eventNotificationService;

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
    session.setGroupId("room-123");
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
    CaseHandoverStatus status =
        caseHandoverService.requestAccess(123L, "OTHER_EMERGENCY", "Colleague is unavailable.");

    assertEquals("GRANTED", status.getStatus());
    assertTrue(status.isCanViewContent());
    assertFalse(status.isClientConsentRequired());
    assertEquals(requester, session.getConsultant());
    verify(sessionRepository).save(session);
    verify(eventNotificationService, atLeastOnce())
        .createEvent(any(), any(), any(), any(), any(), any(), any(), any());
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
    assertEquals(requester, session.getConsultant());
    assertEquals(CaseHandoverRequest.Status.GRANTED, request.getStatus());
    assertEquals("ACCESS_GRANTED", request.getAuditOutcome());
    verify(sessionRepository).save(session);
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
        .reasonLabel("Counsellor asked for advice")
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
