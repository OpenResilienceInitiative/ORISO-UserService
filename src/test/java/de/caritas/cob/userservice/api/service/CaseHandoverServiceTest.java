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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.facade.SessionSupervisorFacade;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private MatrixSessionSystemMessageService matrixSessionSystemMessageService;
  @Mock private SessionSupervisorFacade sessionSupervisorFacade;

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
        .createEvent(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  /**
   * ADR-008 "Supervision (auto-assigned)": a takeover hands the case to a new owner, so the new
   * owner's standing supervisor has to attach. Before this, only the enquiry-accept path did, and a
   * case that changed hands silently ran unsupervised.
   */
  @Test
  void requestAccess_attachesTheNewOwnersStandingSupervisor_WhenGranted() {
    caseHandoverService.requestAccess(123L, "OTHER_EMERGENCY", "Colleague is unavailable.");

    verify(sessionSupervisorFacade).attachStandingSupervisorIfAssigned(123L, requester);
  }

  @Test
  void requestAccess_doesNotAttachAStandingSupervisor_WhenTheHandoverIsNotGranted() {
    when(caseHandoverReasonPolicyRepository.findByEnabledTrueOrderByDisplayOrderAscCodeAsc())
        .thenReturn(
            List.of(reasonPolicy("OTHER_EMERGENCY", "Other emergency", false, false, true, 30)));

    caseHandoverService.requestAccess(123L, "OTHER_EMERGENCY", "Needs cover.");

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
      caseHandoverService.requestAccess(123L, "OTHER_EMERGENCY", "Colleague is unavailable.");

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

  @Test
  void requestAccess_usesOnlyGenericLocalizedTextForAskerNotification() {
    when(eventNotificationService.buildCaseHandoverParams(
            eq(session), anyString(), isNull(), isNull(), isNull()))
        .thenReturn("{\"audience\":\"asker\"}");

    caseHandoverService.requestAccess(
        123L, "COUNSELLOR_IS_ILL", "Client disclosed sensitive information.");

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

    caseHandoverService.requestAccess(
        123L, "COUNSELLOR_ASKED_FOR_ADVICE", "Client disclosed sensitive information.");

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

    caseHandoverService.requestAccess(
        123L, "COUNSELLOR_IS_ILL", "Client disclosed sensitive information.");

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

    caseHandoverService.requestAccess(
        123L, "COUNSELLOR_IS_ILL", "Client disclosed sensitive information.");

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

    caseHandoverService.requestAccess(123L, reasonCode, "Client disclosed sensitive information.");

    verify(matrixSessionSystemMessageService)
        .postCaseHandoverGrantedMessage(eq(session), anyString(), description.capture());
    assertEquals(
        "Requesting Counsellor hat deinen Fall übernommen und führt deine Beratung ab jetzt weiter.",
        description.getValue());
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
    verify(sessionRepository).save(session);
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
