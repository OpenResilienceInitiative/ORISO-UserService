package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.facade.SessionSupervisorFacade;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.CaseHandoverConsentMode;
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
import de.caritas.cob.userservice.api.service.CaseHandoverService.CaseHandoverStatus;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverConsentValue;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
  @Mock private CaseHandoverMatrixRepairService matrixRepairService;
  @Mock private MatrixSessionSystemMessageService matrixSessionSystemMessageService;
  @Mock private SessionSupervisorFacade sessionSupervisorFacade;
  @Mock private ScheduledTaskClaimService scheduledTaskClaimService;
  @Mock private PlatformTransactionManager transactionManager;
  @Mock private TransactionStatus transactionStatus;
  @Spy private Clock clock = Clock.fixed(Instant.parse("2026-08-16T10:00:00Z"), ZoneOffset.UTC);

  // The real rule, not a mock: ConsultantDisplayNameResolver is the single place that decides
  // which counsellor name may appear in client-facing handover copy (ADR-002 §2, #1200).
  @Spy
  private ConsultantDisplayNameResolver consultantDisplayNameResolver =
      new ConsultantDisplayNameResolver();

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
    when(caseHandoverPolicyCacheService.getEffective(any())).thenReturn(defaultTenantPolicies());
    when(caseHandoverRequestRepository.findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
            123L, "requester"))
        .thenReturn(List.of());
    when(caseHandoverRequestRepository.findBySessionIdAndStatusOrderByCreatedAtDesc(
            123L, CaseHandoverRequest.Status.GRANTED))
        .thenReturn(List.of());
    when(caseHandoverRequestRepository.save(any(CaseHandoverRequest.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(matrixSynapseService.getRoomMembers(anyString())).thenReturn(Optional.of(List.of()));
    when(scheduledTaskClaimService.tryClaim(anyString(), any())).thenReturn(true);
    when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
  }

  @Test
  void requestAccess_failsClosedWhenNoTenantPolicyCanBeEnforced() {
    when(caseHandoverPolicyCacheService.getEffective(7L))
        .thenThrow(new ServiceUnavailableException("synthetic outage"));

    assertThrows(
        ServiceUnavailableException.class,
        () -> caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "cover"));

    verify(caseHandoverRequestRepository, never()).save(any());
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
  void listReasons_mapsTheExplicitTenantOptOutWithoutInventingAnApprovalRole() {
    when(caseHandoverPolicyCacheService.getEffective(7L))
        .thenReturn(
            tenantPolicies(
                "Rat benötigt",
                180,
                de.caritas.cob.userservice.tenantadminservice.generated.web.model
                    .CaseHandoverConsentValue.OPT_OUT,
                Set.of()));

    var reason = caseHandoverService.listReasons(7L).get(0);

    assertEquals(CaseHandoverConsentMode.OPT_OUT, reason.getClientConsent());
    assertFalse(reason.isClientConsentRequired());
  }

  @Test
  void listReasons_preservesLegacyClientConsentPolicyMode() {
    var policies = tenantPolicies("Rat benötigt", 180);
    var advice = policies.getReasons().get("COUNSELLOR_ASKED_FOR_ADVICE");
    advice.setClientConsent(null);
    advice
        .getClientConsentRequired()
        .setMode(
            de.caritas.cob.userservice.tenantadminservice.generated.web.model.PermissionPolicyMode
                .SUGGESTED);
    when(caseHandoverPolicyCacheService.getEffective(7L)).thenReturn(policies);

    var reason = caseHandoverService.listReasons(7L).get(0);

    assertEquals("SUGGESTED", reason.getClientConsentMode());
  }

  @Test
  void listReasonsTreatsAnExplicitlyEmptyTenantPolicyAsNoAllowedReasons() {
    when(caseHandoverPolicyCacheService.getEffective(7L))
        .thenReturn(
            new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                    .CaseHandoverPolicies()
                .reasons(Map.of()));

    assertTrue(caseHandoverService.listReasons(7L).isEmpty());
    verify(caseHandoverReasonPolicyRepository, never())
        .findByEnabledTrueOrderByDisplayOrderAscCodeAsc();
  }

  @Test
  void requestAccessUsesTenantContextForLegacySessionsWithoutTenantId() {
    session.setTenantId(null);
    TenantContext.setCurrentTenant(7L);
    try {
      caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Cover");
    } finally {
      TenantContext.clear();
    }

    verify(caseHandoverPolicyCacheService, atLeastOnce()).getEffective(7L);
  }

  @Test
  void listReasonsRejectsApprovalRolesWithoutAnImplementedWorkflow() {
    when(caseHandoverPolicyCacheService.getEffective(7L))
        .thenReturn(
            tenantPolicies(
                "Rat benötigt", 180, CaseHandoverConsentValue.OPT_IN, Set.of("SUPERVISOR")));

    assertThrows(ServiceUnavailableException.class, () -> caseHandoverService.listReasons(7L));
  }

  @Test
  void updateReasonPoliciesWritesThroughToTenantServiceOwnedPolicy() {
    when(caseHandoverPolicyCacheService.updateEffective(eq(7L), any()))
        .thenAnswer(invocation -> invocation.getArgument(1));
    TenantContext.setCurrentTenant(7L);
    try {
      var updated =
          caseHandoverService.updateReasonPolicies(
              List.of(
                  CaseHandoverService.CaseHandoverReason.builder()
                      .code("COUNSELLOR_IS_ILL")
                      .label("Ausfall aktualisiert")
                      .enabled(true)
                      .accessAllowed(true)
                      .clientConsent(CaseHandoverConsentMode.NONE)
                      .build()));

      assertEquals("Ausfall aktualisiert", updated.get(3).getLabel());
      verify(caseHandoverPolicyCacheService).updateEffective(eq(7L), any());
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void updateReasonPolicies_appliesTypedConsentValueAndModeFromAdminPayload() {
    when(caseHandoverPolicyCacheService.updateEffective(eq(7L), any()))
        .thenAnswer(invocation -> invocation.getArgument(1));
    TenantContext.setCurrentTenant(7L);
    try {
      caseHandoverService.updateReasonPolicies(
          List.of(
              CaseHandoverService.CaseHandoverReason.builder()
                  .code("COUNSELLOR_ASKED_FOR_ADVICE")
                  .label("Rat benötigt")
                  .enabled(true)
                  .accessAllowed(true)
                  .clientConsent(CaseHandoverConsentMode.OPT_OUT)
                  .clientConsentMode("SUGGESTED")
                  .clientConsentRequired(false)
                  .maxAccessDurationMinutes(180)
                  .build()));

      ArgumentCaptor<
              de.caritas.cob.userservice.tenantadminservice.generated.web.model
                  .CaseHandoverPolicies>
          written =
              ArgumentCaptor.forClass(
                  de.caritas.cob.userservice.tenantadminservice.generated.web.model
                      .CaseHandoverPolicies.class);
      verify(caseHandoverPolicyCacheService).updateEffective(eq(7L), written.capture());
      var advice = written.getValue().getReasons().get("COUNSELLOR_ASKED_FOR_ADVICE");
      assertEquals(
          de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverConsentValue
              .OPT_OUT,
          advice.getClientConsent().getValue());
      assertEquals(
          de.caritas.cob.userservice.tenantadminservice.generated.web.model.PermissionPolicyMode
              .SUGGESTED,
          advice.getClientConsent().getMode());
      assertEquals(
          de.caritas.cob.userservice.tenantadminservice.generated.web.model.PermissionPolicyMode
              .SUGGESTED,
          advice.getClientConsentRequired().getMode());
      assertEquals(Boolean.FALSE, advice.getClientConsentRequired().getValue());
    } finally {
      TenantContext.clear();
    }
  }

  /**
   * #1131 acceptance: an admin picks Opt-Out and a non-default duration, saves, reloads. Both
   * values have to survive the write-through and come back on the response the card re-reads.
   */
  @Test
  void updateReasonPolicies_persistsAChangedDurationAndReturnsItOnTheSameResponse() {
    when(caseHandoverPolicyCacheService.updateEffective(eq(7L), any()))
        .thenAnswer(invocation -> invocation.getArgument(1));
    TenantContext.setCurrentTenant(7L);
    try {
      var updated =
          caseHandoverService.updateReasonPolicies(
              List.of(
                  CaseHandoverService.CaseHandoverReason.builder()
                      .code("COUNSELLOR_ASKED_FOR_ADVICE")
                      .label("Rat benötigt")
                      .enabled(true)
                      .accessAllowed(true)
                      .clientConsent(CaseHandoverConsentMode.OPT_OUT)
                      .clientConsentMode("SUGGESTED")
                      .clientConsentRequired(false)
                      .maxAccessDurationMinutes(90)
                      .build()));

      ArgumentCaptor<
              de.caritas.cob.userservice.tenantadminservice.generated.web.model
                  .CaseHandoverPolicies>
          written =
              ArgumentCaptor.forClass(
                  de.caritas.cob.userservice.tenantadminservice.generated.web.model
                      .CaseHandoverPolicies.class);
      verify(caseHandoverPolicyCacheService).updateEffective(eq(7L), written.capture());
      var advice = written.getValue().getReasons().get("COUNSELLOR_ASKED_FOR_ADVICE");
      assertEquals(90, advice.getMaxAccessDurationMinutes().getValue());

      var response =
          updated.stream()
              .filter(reason -> "ADVICE_REQUESTED".equals(reason.getCode()))
              .findFirst()
              .orElseThrow();
      assertEquals(90, response.getMaxAccessDurationMinutes());
      assertEquals(CaseHandoverConsentMode.OPT_OUT, response.getClientConsent());
      assertEquals("SUGGESTED", response.getClientConsentMode());
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void updateReasonPolicies_rejectsUnknownPolicyMode() {
    TenantContext.setCurrentTenant(7L);
    try {
      assertThrows(
          BadRequestException.class,
          () ->
              caseHandoverService.updateReasonPolicies(
                  List.of(
                      CaseHandoverService.CaseHandoverReason.builder()
                          .code("COUNSELLOR_ASKED_FOR_ADVICE")
                          .label("Rat benötigt")
                          .enabled(true)
                          .accessAllowed(true)
                          .clientConsent(CaseHandoverConsentMode.OPT_IN)
                          .clientConsentMode("LOCKED")
                          .build())));
    } finally {
      TenantContext.clear();
    }
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
  void requestAccess_optOutGrantsCoAccessImmediatelyAndLeavesTheClientDecisionOpen() {
    when(caseHandoverPolicyCacheService.getEffective(7L))
        .thenReturn(
            tenantPolicies(
                "Rat benötigt",
                180,
                de.caritas.cob.userservice.tenantadminservice.generated.web.model
                    .CaseHandoverConsentValue.OPT_OUT,
                Set.of()));

    var status =
        caseHandoverService.requestAccess(123L, "COUNSELLOR_ASKED_FOR_ADVICE", "Zweitmeinung");

    assertEquals("GRANTED_PENDING_CLIENT_OPTOUT", status.getStatus());
    assertTrue(status.isCanViewContent());
    assertEquals(CaseHandoverConsentMode.OPT_OUT, status.getClientConsent());
    ArgumentCaptor<CaseHandoverRequest> request =
        ArgumentCaptor.forClass(CaseHandoverRequest.class);
    verify(caseHandoverRequestRepository).save(request.capture());
    assertEquals(
        CaseHandoverRequest.Status.GRANTED_PENDING_CLIENT_OPTOUT, request.getValue().getStatus());
    assertEquals(CaseHandoverConsentMode.OPT_OUT, request.getValue().getClientConsent());
    assertEquals(LocalDateTime.of(2026, 8, 16, 13, 0), request.getValue().getExpiresAt());
  }

  @ParameterizedTest
  @CsvSource({
    "de, 'Vorläufiger Zugriff einer Beratungsperson', 'Requesting Counsellor hat vorläufig Zugriff auf deinen Fall. Du kannst diesen Zugriff ablehnen.'",
    "en, 'Temporary counsellor access', 'Requesting Counsellor currently has access to your case. You can decline this access.'"
  })
  void requestAccess_optOutNotificationSaysAccessIsAlreadyActive(
      String language, String expectedTitle, String expectedDescription) {
    session.setLanguageCode(LanguageCode.getByCode(language));
    when(caseHandoverPolicyCacheService.getEffective(7L))
        .thenReturn(
            tenantPolicies(
                "Rat benötigt",
                180,
                de.caritas.cob.userservice.tenantadminservice.generated.web.model
                    .CaseHandoverConsentValue.OPT_OUT,
                Set.of()));
    when(caseHandoverRequestRepository.save(any(CaseHandoverRequest.class)))
        .thenAnswer(
            invocation -> {
              CaseHandoverRequest saved = invocation.getArgument(0);
              saved.setId(88L);
              return saved;
            });

    caseHandoverService.requestAccess(123L, "COUNSELLOR_ASKED_FOR_ADVICE", "Zweitmeinung");

    verify(eventNotificationService)
        .createEvent(
            eq("asker"),
            eq("case.handover.consent.requested"),
            eq(EventNotificationService.CATEGORY_SYSTEM),
            eq(expectedTitle),
            eq(expectedDescription),
            any(),
            anyString(),
            eq(123L),
            eq(7L));
  }

  @Test
  void resolveClientConsent_optOutApprovalKeepsStaffPolicyDetailsOutOfClientResponse() {
    var request =
        CaseHandoverRequest.builder()
            .id(103L)
            .session(session)
            .requesterConsultant(requester)
            .previousConsultant(previous)
            .reasonCode("COUNSELLOR_ASKED_FOR_ADVICE")
            .reasonLabel("Internal reason")
            .explanation("Internal explanation")
            .status(CaseHandoverRequest.Status.GRANTED_PENDING_CLIENT_OPTOUT)
            .accessType(CaseHandoverRequest.AccessType.CO_ACCESS)
            .clientConsent(CaseHandoverConsentMode.OPT_OUT)
            .policyAuthority("tenant-service-resolved")
            .auditOutcome("ACCESS_GRANTED_PENDING_CLIENT_OPTOUT")
            .createdAt(LocalDateTime.of(2026, 8, 16, 10, 0))
            .matrixMembershipAdded(true)
            .tenantId(7L)
            .build();
    when(caseHandoverRequestRepository.findByIdAndSessionId(103L, 123L))
        .thenReturn(Optional.of(request));

    var status = caseHandoverService.resolveClientConsent(123L, 103L, true);

    assertEquals("GRANTED", status.getStatus());
    assertNull(status.getReasonCode());
    assertNull(status.getReasonLabel());
    assertNull(status.getPolicyAuthority());
  }

  @Test
  void resolveClientConsent_acceptsLegacyPendingStatusCreatedBeforeConsentModeMigration() {
    var request =
        CaseHandoverRequest.builder()
            .id(104L)
            .session(session)
            .requesterConsultant(requester)
            .previousConsultant(previous)
            .reasonCode("COUNSELLOR_ASKED_FOR_ADVICE")
            .status(CaseHandoverRequest.Status.PENDING)
            .clientConsent(CaseHandoverConsentMode.OPT_IN)
            .createdAt(LocalDateTime.of(2026, 8, 16, 10, 0))
            .tenantId(7L)
            .build();
    when(caseHandoverRequestRepository.findByIdAndSessionId(104L, 123L))
        .thenReturn(Optional.of(request));

    var status = caseHandoverService.resolveClientConsent(123L, 104L, false);

    assertEquals("CLIENT_CONSENT_DECLINED", status.getStatus());
    verify(caseHandoverRequestRepository).save(request);
  }

  @Test
  void resolveClientConsent_optOutDeclineImmediatelyRevokesCoAccess() {
    var request =
        CaseHandoverRequest.builder()
            .id(101L)
            .session(session)
            .requesterConsultant(requester)
            .previousConsultant(previous)
            .reasonCode("COUNSELLOR_ASKED_FOR_ADVICE")
            .reasonLabel("Rat benötigt")
            .explanation("Zweitmeinung")
            .status(CaseHandoverRequest.Status.GRANTED_PENDING_CLIENT_OPTOUT)
            .accessType(CaseHandoverRequest.AccessType.CO_ACCESS)
            .clientConsent(CaseHandoverConsentMode.OPT_OUT)
            .clientConsentRequired(false)
            .policyAuthority("tenant-service-resolved")
            .auditOutcome("ACCESS_GRANTED_PENDING_CLIENT_OPTOUT")
            .createdAt(LocalDateTime.of(2026, 8, 16, 10, 0))
            .matrixMembershipAdded(true)
            .tenantId(7L)
            .build();
    when(caseHandoverRequestRepository.findByIdAndSessionId(101L, 123L))
        .thenReturn(Optional.of(request));
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    previous.setMatrixUserId("@previous:matrix");
    when(matrixSynapseService.getRoomMembers("!room:matrix"))
        .thenReturn(Optional.of(java.util.List.of("@requester:matrix", "@previous:matrix")));
    when(matrixSynapseService.loginAsUserAccessToken("@previous:matrix"))
        .thenReturn("previous-token");
    when(matrixSynapseService.removeUserFromRoom(
            "!room:matrix", "@requester:matrix", "previous-token"))
        .thenReturn(true);

    var status = caseHandoverService.resolveClientConsent(123L, 101L, false);

    assertEquals("CLIENT_CONSENT_DECLINED", status.getStatus());
    assertFalse(status.isCanViewContent());
    assertEquals("CLIENT_CONSENT_DECLINED", status.getAuditOutcome());
    verify(matrixSynapseService)
        .removeUserFromRoom("!room:matrix", "@requester:matrix", "previous-token");
  }

  @Test
  void resolveClientConsent_optOutDeclineDoesNotReverseACompletedTakeover() {
    session.setConsultant(requester);
    var request =
        CaseHandoverRequest.builder()
            .id(102L)
            .session(session)
            .requesterConsultant(requester)
            .previousConsultant(previous)
            .reasonCode("COUNSELLOR_IS_ILL")
            .reasonLabel("Unplanned absence")
            .explanation("Vertretung")
            .status(CaseHandoverRequest.Status.GRANTED_PENDING_CLIENT_OPTOUT)
            .accessType(CaseHandoverRequest.AccessType.TAKEOVER)
            .clientConsent(CaseHandoverConsentMode.OPT_OUT)
            .clientConsentRequired(false)
            .policyAuthority("tenant-service-resolved")
            .auditOutcome("ACCESS_GRANTED_PENDING_CLIENT_OPTOUT")
            .createdAt(LocalDateTime.of(2026, 8, 16, 10, 0))
            .tenantId(7L)
            .build();
    when(caseHandoverRequestRepository.findByIdAndSessionId(102L, 123L))
        .thenReturn(Optional.of(request));

    var status = caseHandoverService.resolveClientConsent(123L, 102L, false);

    assertEquals("GRANTED", status.getStatus());
    assertEquals("CLIENT_OPTOUT_DECLINED_AFTER_TAKEOVER", status.getAuditOutcome());
    assertEquals(requester, session.getConsultant());
  }

  @Test
  void requestAccess_grantsAndActivatesCounsellor_WhenPolicyDoesNotRequireClientConsent() {
    CaseHandoverStatus status =
        caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Colleague is unavailable.");

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
    caseHandoverService.requestAccess(123L, "COUNSELLOR_LEFT", "Colleague is unavailable.");

    verify(sessionSupervisorFacade).attachStandingSupervisorIfAssigned(123L, requester);
  }

  @Test
  void requestAccess_doesNotAttachAStandingSupervisor_WhenTheHandoverIsNotGranted() {
    when(caseHandoverPolicyCacheService.getEffective(7L))
        .thenReturn(
            new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                    .CaseHandoverPolicies()
                .reasons(
                    Map.of(
                        "OTHER_EMERGENCY",
                        tenantPolicy(
                            "OTHER_EMERGENCY",
                            "Other emergency",
                            de.caritas.cob.userservice.tenantadminservice.generated.web.model
                                .CaseHandoverConsentValue.NONE,
                            true,
                            false,
                            null))));

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
      caseHandoverService.requestAccess(123L, "COUNSELLOR_LEFT", "Colleague is unavailable.");

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
            eq(session), anyString(), isNull(), isNull(), eq(88L), eq("OPT_IN")))
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
        .buildCaseHandoverParams(
            eq(session), anyString(), isNull(), isNull(), eq(88L), eq("OPT_IN"));
  }

  @ParameterizedTest
  @CsvSource({
    "de, 'New counsellor took over your case', 'Requesting Counsellor hat deinen Fall übernommen und führt deine Beratung ab jetzt weiter.'",
    "en, 'New counsellor took over your case', 'Requesting Counsellor has taken over your case and will continue your counselling from now on.'",
    "fr, 'New counsellor took over your case', 'Requesting Counsellor a repris votre dossier et poursuivra désormais votre accompagnement.'",
    "ru, 'New counsellor took over your case', 'Requesting Counsellor принял(а) ваше дело и с этого момента продолжит консультирование.'",
    "tr, 'New counsellor took over your case', 'Requesting Counsellor vakanızı devraldı ve bundan sonra danışmanlığınıza devam edecek.'",
    "uk, 'New counsellor took over your case', 'Requesting Counsellor перейняв(-ла) вашу справу й відтепер продовжуватиме консультування.'",
    "ti, 'New counsellor took over your case', 'Requesting Counsellor ጉዳይካ ተረኪቡ ካብ ሕጂ ንደሓር ምኽሪ ክቕጽል እዩ።'"
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
            eq("New counsellor took over your case"),
            eq(
                "Requesting Counsellor hat deinen Fall übernommen und führt deine Beratung ab jetzt weiter."),
            eq("{\"audience\":\"asker\"}"),
            anyString(),
            eq(123L),
            eq(7L));
  }

  @ParameterizedTest
  @ValueSource(strings = {"COUNSELLOR_ON_HOLIDAY", "COUNSELLOR_IS_ILL", "COUNSELLOR_LEFT"})
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
        .buildCaseHandoverParams(any(), anyString(), eq("UNPLANNED_ABSENCE"), any(), any());
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

    caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Colleague is unavailable.");

    verify(matrixSynapseService)
        .inviteUserToRoom("!room:matrix", "@requester:matrix", "previous-token");
    verify(matrixSynapseService).joinRoom("!room:matrix", "requester-token");
    // ADR-002: a takeover re-hides the original counsellor but keeps their membership, so they
    // can reclaim the case. Removing them here would make the history unrecoverable under Megolm.
    verify(matrixSynapseService, never()).leaveRoom(anyString(), anyString());
  }

  @Test
  void requestAccess_failsWhenExistingMatrixMembershipCannotBeVerified() {
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    previous.setMatrixUserId("@previous:matrix");
    when(matrixSynapseService.loginAsUserAccessToken("@previous:matrix"))
        .thenReturn("previous-token");
    when(matrixSynapseService.loginAsUserAccessToken("@requester:matrix"))
        .thenReturn("requester-token");
    when(matrixSynapseService.getRoomMembers("!room:matrix")).thenReturn(Optional.empty());

    assertThrows(
        ServiceUnavailableException.class,
        () ->
            caseHandoverService.requestAccess(
                123L, "COUNSELLOR_IS_ILL", "Colleague is unavailable."));

    verify(matrixSynapseService, never()).joinRoom(anyString(), anyString());
    verify(caseHandoverRequestRepository, never()).save(any());
  }

  /**
   * The Matrix join happens inside the granting transaction. When that transaction rolls back after
   * the join, the membership must be compensated - otherwise the requester keeps reading chat
   * content without any persisted grant.
   */
  @Test
  void requestAccess_removesTheJoinedRequesterAgainWhenTheGrantingTransactionRollsBack() {
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    previous.setMatrixUserId("@previous:matrix");
    when(matrixSynapseService.loginAsUserAccessToken("@previous:matrix"))
        .thenReturn("previous-token");
    when(matrixSynapseService.loginAsUserAccessToken("@requester:matrix"))
        .thenReturn("requester-token");
    when(matrixSynapseService.getRoomMembers("!room:matrix"))
        .thenReturn(Optional.of(List.of("@previous:matrix")));
    when(matrixSynapseService.joinRoom("!room:matrix", "requester-token")).thenReturn(true);
    when(matrixSynapseService.removeUserFromRoom(
            "!room:matrix", "@requester:matrix", "previous-token"))
        .thenReturn(true);

    TransactionSynchronizationManager.initSynchronization();
    try {
      caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Colleague is unavailable.");
      var registered = List.copyOf(TransactionSynchronizationManager.getSynchronizations());
      assertFalse(registered.isEmpty());
      verify(matrixSynapseService, never())
          .removeUserFromRoom(anyString(), anyString(), anyString());
      verify(caseHandoverRequestRepository)
          .save(
              org.mockito.ArgumentMatchers.argThat(
                  request -> Boolean.TRUE.equals(request.getMatrixMembershipAdded())));

      registered.forEach(
          synchronization ->
              synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    verify(matrixSynapseService)
        .removeUserFromRoom("!room:matrix", "@requester:matrix", "previous-token");
  }

  @Test
  void requestAccess_queuesDurableRepairWhenRollbackRemovalFails() {
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    previous.setMatrixUserId("@previous:matrix");
    when(matrixSynapseService.loginAsUserAccessToken("@previous:matrix"))
        .thenReturn("previous-token");
    when(matrixSynapseService.loginAsUserAccessToken("@requester:matrix"))
        .thenReturn("requester-token");
    when(matrixSynapseService.getRoomMembers("!room:matrix"))
        .thenReturn(Optional.of(List.of("@previous:matrix")));
    when(matrixSynapseService.joinRoom("!room:matrix", "requester-token")).thenReturn(true);
    when(matrixSynapseService.removeUserFromRoom(
            "!room:matrix", "@requester:matrix", "previous-token"))
        .thenReturn(false);

    TransactionSynchronizationManager.initSynchronization();
    try {
      caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Unavailable");
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(
              synchronization ->
                  synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    verify(matrixRepairService)
        .enqueueRemoval("!room:matrix", "@requester:matrix", "@previous:matrix", 123L, "requester");
  }

  /** A commit must never trigger the rollback compensation. */
  @Test
  void requestAccess_keepsTheJoinedRequesterWhenTheGrantingTransactionCommits() {
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    previous.setMatrixUserId("@previous:matrix");
    when(matrixSynapseService.loginAsUserAccessToken("@previous:matrix"))
        .thenReturn("previous-token");
    when(matrixSynapseService.loginAsUserAccessToken("@requester:matrix"))
        .thenReturn("requester-token");
    when(matrixSynapseService.getRoomMembers("!room:matrix"))
        .thenReturn(Optional.of(List.of("@previous:matrix")));
    when(matrixSynapseService.joinRoom("!room:matrix", "requester-token")).thenReturn(true);

    TransactionSynchronizationManager.initSynchronization();
    try {
      caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Colleague is unavailable.");
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(
              synchronization ->
                  synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    verify(matrixSynapseService, never()).removeUserFromRoom(anyString(), anyString(), anyString());
  }

  /**
   * ADR-002: department counsellors are already room members. The rollback compensation must never
   * remove a membership the handover did not create.
   */
  @Test
  void requestAccess_registersNoRollbackCompensationForAPreexistingRoomMember() {
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    previous.setMatrixUserId("@previous:matrix");
    when(matrixSynapseService.loginAsUserAccessToken("@previous:matrix"))
        .thenReturn("previous-token");
    when(matrixSynapseService.loginAsUserAccessToken("@requester:matrix"))
        .thenReturn("requester-token");
    when(matrixSynapseService.getRoomMembers("!room:matrix"))
        .thenReturn(Optional.of(List.of("@previous:matrix", "@requester:matrix")));
    when(matrixSynapseService.joinRoom("!room:matrix", "requester-token")).thenReturn(true);

    TransactionSynchronizationManager.initSynchronization();
    try {
      caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Colleague is unavailable.");
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(
              synchronization ->
                  synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
      verify(matrixSynapseService, never())
          .removeUserFromRoom(anyString(), anyString(), anyString());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void requestAccess_rendersTheTenantConfiguredClientTemplateInTheGrantNotification() {
    when(caseHandoverPolicyCacheService.getEffective(7L))
        .thenReturn(
            tenantPolicies(
                "Rat ben\u00f6tigt",
                180,
                de.caritas.cob.userservice.tenantadminservice.generated.web.model
                    .CaseHandoverConsentValue.OPT_OUT,
                Set.of()));
    when(eventNotificationService.buildCaseHandoverParams(
            eq(session), anyString(), isNull(), isNull(), isNull()))
        .thenReturn("{\"audience\":\"asker\"}");

    caseHandoverService.requestAccess(123L, "COUNSELLOR_ASKED_FOR_ADVICE", "Zweitmeinung");

    verify(eventNotificationService)
        .createEvent(
            eq("asker"),
            eq("case.handover.granted"),
            eq(EventNotificationService.CATEGORY_SYSTEM),
            anyString(),
            eq("Requesting Counsellor kann 3 Stunden zeitlich begrenzt mitlesen."),
            eq("{\"audience\":\"asker\"}"),
            anyString(),
            eq(123L),
            eq(7L));
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
        caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Colleague is unavailable.");

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
                123L, "COUNSELLOR_IS_ILL", "Colleague is unavailable."));

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

    caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Colleague is unavailable.");

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
        caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Urgent cover.");

    assertEquals("TAKEOVER", status.getAccessType());
    assertNull(status.getExpiresAt());
    assertEquals(requester, session.getConsultant());
    verify(caseHandoverRequestRepository).save(savedRequest.capture());
    assertNull(savedRequest.getValue().getMaxAccessDurationMinutes());
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
    request.setExpiresAt(LocalDateTime.of(2026, 8, 16, 10, 0));
    request.setMatrixMembershipAdded(true);
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
    when(caseHandoverRequestRepository.findByIdForUpdate(request.getId()))
        .thenReturn(Optional.of(request));

    assertEquals(1, caseHandoverService.expireCoAccess());

    assertEquals(CaseHandoverRequest.Status.EXPIRED, request.getStatus());
    assertEquals("ACCESS_EXPIRED", request.getAuditOutcome());
    verify(matrixSynapseService)
        .removeUserFromRoom("!room:matrix", "@requester:matrix", "previous-token");
    verify(caseHandoverRequestRepository).save(request);
    var definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager, org.mockito.Mockito.times(2)).getTransaction(definitions.capture());
    assertTrue(
        definitions.getAllValues().stream()
            .allMatch(
                definition ->
                    definition.getPropagationBehavior()
                        == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
  }

  @Test
  void expireCoAccessDoesNotRemoveMembershipRequiredByANewerActiveGrant() {
    CaseHandoverRequest expired = grantedAdviceRequest();
    expired.setExpiresAt(LocalDateTime.of(2026, 8, 16, 10, 0));
    expired.setMatrixMembershipAdded(true);
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    CaseHandoverRequest newerGrant = grantedAdviceRequest();
    newerGrant.setId(101L);
    newerGrant.setExpiresAt(LocalDateTime.of(2026, 8, 16, 11, 0));
    newerGrant.setMatrixMembershipAdded(false);
    when(caseHandoverRequestRepository.findByStatusAndAccessTypeAndExpiresAtLessThanEqual(
            CaseHandoverRequest.Status.GRANTED,
            CaseHandoverRequest.AccessType.CO_ACCESS,
            LocalDateTime.of(2026, 8, 16, 10, 0)))
        .thenReturn(List.of(expired));
    when(caseHandoverRequestRepository.findActiveGrantExcluding(
            eq(123L),
            eq("requester"),
            eq(expired.getId()),
            eq(
                List.of(
                    CaseHandoverRequest.Status.GRANTED,
                    CaseHandoverRequest.Status.GRANTED_PENDING_CLIENT_OPTOUT)),
            eq(LocalDateTime.of(2026, 8, 16, 10, 0)),
            any()))
        .thenReturn(List.of(newerGrant));
    when(caseHandoverRequestRepository.findByIdForUpdate(expired.getId()))
        .thenReturn(Optional.of(expired));

    assertEquals(1, caseHandoverService.expireCoAccess());

    assertEquals(CaseHandoverRequest.Status.EXPIRED, expired.getStatus());
    assertTrue(newerGrant.getMatrixMembershipAdded());
    verify(caseHandoverRequestRepository).save(newerGrant);
    var page = ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
    verify(caseHandoverRequestRepository)
        .findActiveGrantExcluding(
            eq(123L),
            eq("requester"),
            eq(expired.getId()),
            eq(
                List.of(
                    CaseHandoverRequest.Status.GRANTED,
                    CaseHandoverRequest.Status.GRANTED_PENDING_CLIENT_OPTOUT)),
            eq(LocalDateTime.of(2026, 8, 16, 10, 0)),
            page.capture());
    assertEquals(1, page.getValue().getPageSize());
    verifyNoMatrixRemoval();
  }

  @Test
  void expireCoAccessKeepsStandingMembershipForLegacyAndPreProvisionedRequests() {
    CaseHandoverRequest request = grantedAdviceRequest();
    request.setMatrixMembershipAdded(null);
    request.setExpiresAt(LocalDateTime.of(2026, 8, 16, 10, 0));
    when(caseHandoverRequestRepository.findByStatusAndAccessTypeAndExpiresAtLessThanEqual(
            CaseHandoverRequest.Status.GRANTED,
            CaseHandoverRequest.AccessType.CO_ACCESS,
            LocalDateTime.of(2026, 8, 16, 10, 0)))
        .thenReturn(List.of(request));
    when(caseHandoverRequestRepository.findByIdForUpdate(request.getId()))
        .thenReturn(Optional.of(request));

    assertEquals(1, caseHandoverService.expireCoAccess());

    assertEquals(CaseHandoverRequest.Status.EXPIRED, request.getStatus());
    verifyNoMatrixRemoval();
  }

  private void verifyNoMatrixRemoval() {
    verify(matrixSynapseService, never()).removeUserFromRoom(anyString(), anyString(), anyString());
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
    verify(caseHandoverRequestRepository, never()).save(request);
  }

  @Test
  void expireCoAccess_doesNotRemoveWhenMembershipLookupIsUnknown() {
    CaseHandoverRequest request = grantedAdviceRequest();
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    when(matrixSynapseService.getRoomMembers("!room:matrix")).thenReturn(Optional.empty());
    when(caseHandoverRequestRepository.findByStatusAndAccessTypeAndExpiresAtLessThanEqual(
            CaseHandoverRequest.Status.GRANTED,
            CaseHandoverRequest.AccessType.CO_ACCESS,
            LocalDateTime.of(2026, 8, 16, 10, 0)))
        .thenReturn(List.of(request));

    assertEquals(0, caseHandoverService.expireCoAccess());

    assertEquals(CaseHandoverRequest.Status.GRANTED, request.getStatus());
    verify(matrixSynapseService, never()).removeUserFromRoom(anyString(), anyString(), anyString());
  }

  @Test
  void expireCoAccess_keepsProcessingWhenMatrixReconciliationFails() {
    CaseHandoverRequest failingRequest = grantedAdviceRequest();
    failingRequest.setId(100L);
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    when(matrixSynapseService.getRoomMembers("!room:matrix"))
        .thenThrow(new IllegalStateException("Matrix unavailable"));
    Session roomlessSession = new Session();
    roomlessSession.setId(124L);
    roomlessSession.setTenantId(7L);
    CaseHandoverRequest healthyRequest = grantedAdviceRequest();
    healthyRequest.setId(101L);
    healthyRequest.setSession(roomlessSession);
    healthyRequest.setExpiresAt(LocalDateTime.of(2026, 8, 16, 10, 0));
    when(caseHandoverRequestRepository.findByStatusAndAccessTypeAndExpiresAtLessThanEqual(
            CaseHandoverRequest.Status.GRANTED,
            CaseHandoverRequest.AccessType.CO_ACCESS,
            LocalDateTime.of(2026, 8, 16, 10, 0)))
        .thenReturn(List.of(failingRequest, healthyRequest));
    when(caseHandoverRequestRepository.findByIdForUpdate(healthyRequest.getId()))
        .thenReturn(Optional.of(healthyRequest));

    assertEquals(1, caseHandoverService.expireCoAccess());

    assertEquals(CaseHandoverRequest.Status.GRANTED, failingRequest.getStatus());
    assertEquals(CaseHandoverRequest.Status.EXPIRED, healthyRequest.getStatus());
    verify(caseHandoverRequestRepository).save(healthyRequest);
  }

  @Test
  void expirySchedulerEntrypoint_isVoidForSharedSchedulerAdvice() throws Exception {
    var method = CaseHandoverService.class.getMethod("expireCoAccessSchedule");

    assertEquals(void.class, method.getReturnType());
    assertTrue(
        method.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class));
    assertFalse(
        method.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class));
    assertFalse(
        CaseHandoverService.class
            .getMethod("expireCoAccess")
            .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class));
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
  void requestAccess_deniesAndKeepsContentLocked_WhenPolicyDoesNotAllowReason() {
    when(caseHandoverPolicyCacheService.getEffective(7L))
        .thenReturn(
            new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                    .CaseHandoverPolicies()
                .reasons(
                    Map.of(
                        "COUNSELLOR_IS_ILL",
                        tenantPolicy(
                            "COUNSELLOR_IS_ILL",
                            "Unplanned absence",
                            de.caritas.cob.userservice.tenantadminservice.generated.web.model
                                .CaseHandoverConsentValue.NONE,
                            true,
                            false,
                            null))));

    CaseHandoverStatus status =
        caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Needs cover.");

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
        caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Needs cover.");

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

    caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Illness cover.");

    verify(caseHandoverRequestRepository).save(captor.capture());
    CaseHandoverRequest saved = captor.getValue();
    assertEquals("UNPLANNED_ABSENCE", saved.getReasonCode());
    assertEquals("Unplanned absence", saved.getReasonLabel());
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

  /**
   * A client-approved handover transfers ownership just as a granted requestAccess does, so the new
   * owner's standing supervisor has to attach on this path too. Without this test a regression on
   * the resolveClientConsent branch passes the whole suite.
   */
  @Test
  void resolveClientConsent_attachesTheNewOwnersStandingSupervisor_WhenClientApproves() {
    CaseHandoverRequest request = pendingTakeoverConsentRequest();
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
    CaseHandoverRequest request = pendingTakeoverConsentRequest();
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
      caseHandoverService.requestAccess(123L, "COUNSELLOR_LEFT", "Colleague is unavailable.");

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
            description.capture());
    assertTrue(description.getValue().contains("zeitlich begrenzten Einblick"));
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
        ForbiddenException.class,
        () -> caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Cover."));
  }

  @Test
  void getStatus_forbidsASessionOutsideTheRequesterDepartment() {
    givenRequesterTopics(5L);
    session.setMainTopicId(99L);

    assertThrows(ForbiddenException.class, () -> caseHandoverService.getStatus(123L));
  }

  // ---------------------------------------------------------------------------
  // ADR-002 §2 / #1200: the in-chat handover message is a persisted m.text event in the advice
  // seeker's OWN room. Its name field stays (the client renders it) — its value must never be the
  // counsellor's real name. The old getFullName() fallback fired exactly when the public display
  // name was blank, i.e. for the population pseudonymity protects.
  // ---------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void requestAccess_neverPutsTheRealNameIntoTheClientVisibleHandoverMessage(String blank) {
    requester.setDisplayName(blank);
    requester.setFirstName("Angela");
    requester.setLastName("Musterfrau");
    requester.setUsername("beraterin1");

    caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Illness cover.");

    ArgumentCaptor<String> advisorName = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
    verify(matrixSessionSystemMessageService)
        .postCaseHandoverGrantedMessage(eq(session), advisorName.capture(), description.capture());

    // The username is what the Matrix ID in the same room already exposes.
    assertEquals("beraterin1", advisorName.getValue());
    assertThat(description.getValue()).doesNotContain("Angela").doesNotContain("Musterfrau");
  }

  @Test
  void requestAccess_stillUsesThePublicDisplayNameWhenOneIsSet() {
    requester.setDisplayName("Frau M.");
    requester.setFirstName("Angela");
    requester.setLastName("Musterfrau");

    caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Illness cover.");

    verify(matrixSessionSystemMessageService)
        .postCaseHandoverGrantedMessage(eq(session), eq("Frau M."), anyString());
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

  private CaseHandoverRequest pendingTakeoverConsentRequest() {
    CaseHandoverRequest request = pendingConsentRequest();
    request.setReasonCode("COUNSELLOR_IS_ILL");
    request.setReasonLabel("Unplanned absence");
    request.setAccessType(CaseHandoverRequest.AccessType.TAKEOVER);
    request.setMaxAccessDurationMinutes(null);
    return request;
  }

  private CaseHandoverRequest grantedRequest(Consultant consultant) {
    return CaseHandoverRequest.builder()
        .id(99L)
        .session(session)
        .requesterConsultant(consultant)
        .previousConsultant(previous)
        .reasonCode("COUNSELLOR_IS_ILL")
        .reasonLabel("Unplanned absence")
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
        .matrixMembershipAdded(true)
        .tenantId(7L)
        .build();
  }

  private de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverPolicies
      defaultTenantPolicies() {
    return new de.caritas.cob.userservice.tenantadminservice.generated.web.model
            .CaseHandoverPolicies()
        .reasons(
            Map.of(
                "COUNSELLOR_ASKED_FOR_ADVICE",
                tenantPolicy(
                    "COUNSELLOR_ASKED_FOR_ADVICE",
                    "Advice needed",
                    de.caritas.cob.userservice.tenantadminservice.generated.web.model
                        .CaseHandoverConsentValue.OPT_IN,
                    true,
                    true,
                    180),
                "COUNSELLOR_ON_HOLIDAY",
                tenantPolicy(
                    "COUNSELLOR_ON_HOLIDAY",
                    "Planned absence",
                    de.caritas.cob.userservice.tenantadminservice.generated.web.model
                        .CaseHandoverConsentValue.NONE,
                    true,
                    true,
                    null),
                "OTHER_EMERGENCY",
                tenantPolicy(
                    "OTHER_EMERGENCY",
                    "Other emergency",
                    de.caritas.cob.userservice.tenantadminservice.generated.web.model
                        .CaseHandoverConsentValue.NONE,
                    false,
                    false,
                    null),
                "COUNSELLOR_IS_ILL",
                tenantPolicy(
                    "COUNSELLOR_IS_ILL",
                    "Unplanned absence",
                    de.caritas.cob.userservice.tenantadminservice.generated.web.model
                        .CaseHandoverConsentValue.NONE,
                    true,
                    true,
                    null),
                "COUNSELLOR_LEFT",
                tenantPolicy(
                    "COUNSELLOR_LEFT",
                    "Counsellor does not work here anymore",
                    de.caritas.cob.userservice.tenantadminservice.generated.web.model
                        .CaseHandoverConsentValue.NONE,
                    true,
                    true,
                    null)));
  }

  private de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverReasonPolicy
      tenantPolicy(
          String code,
          String label,
          de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverConsentValue
              consentValue,
          boolean enabled,
          boolean accessAllowed,
          Integer durationMinutes) {
    var mode =
        de.caritas.cob.userservice.tenantadminservice.generated.web.model.PermissionPolicyMode
            .ENFORCED;
    var enabledPolicy =
        new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                .BooleanPermissionPolicy(null)
            .value(enabled)
            .mode(mode);
    var accessPolicy =
        new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                .BooleanPermissionPolicy(null)
            .value(accessAllowed)
            .mode(mode);
    var policy =
        new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                .CaseHandoverReasonPolicy()
            .code(
                de.caritas.cob.userservice.tenantadminservice.generated.web.model
                    .CaseHandoverReasonPolicy.CodeEnum.fromValue(code))
            .labels(
                new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                        .MultilingualTextPermissionPolicy(null)
                    .value(Map.of("de", label, "en", label))
                    .mode(mode))
            .enabled(enabledPolicy)
            .accessAllowed(accessPolicy)
            .clientConsent(
                new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                        .ConsentPermissionPolicy(null)
                    .value(consentValue)
                    .mode(mode))
            .clientConsentRequired(
                new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                        .BooleanPermissionPolicy(null)
                    .value(
                        consentValue
                            == de.caritas.cob.userservice.tenantadminservice.generated.web.model
                                .CaseHandoverConsentValue.OPT_IN)
                    .mode(mode));
    if (durationMinutes != null) {
      policy.maxAccessDurationMinutes(
          new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                  .IntegerPermissionPolicy(null)
              .value(durationMinutes)
              .mode(mode));
    }
    return policy;
  }

  private de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverPolicies
      tenantPolicies(String germanLabel, int durationMinutes) {
    return tenantPolicies(
        germanLabel,
        durationMinutes,
        de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverConsentValue
            .OPT_IN,
        Set.of("CLIENT"));
  }

  private de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverPolicies
      tenantPolicies(
          String germanLabel,
          int durationMinutes,
          de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverConsentValue
              consentValue,
          Set<String> approvalRoleValues) {
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
            .value(approvalRoleValues)
            .mode(mode);
    var consent =
        new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                .ConsentPermissionPolicy(null)
            .value(consentValue)
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
            .clientConsent(consent)
            .clientConsentRequired(
                new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                        .BooleanPermissionPolicy(null)
                    .value(
                        consentValue
                            == de.caritas.cob.userservice.tenantadminservice.generated.web.model
                                .CaseHandoverConsentValue.OPT_IN)
                    .mode(mode))
            .approvalRoles(roles)
            .clientNotificationTemplates(templates)
            .maxAccessDurationMinutes(duration);
    return new de.caritas.cob.userservice.tenantadminservice.generated.web.model
            .CaseHandoverPolicies()
        .reasons(java.util.Map.of(advice.getCode().getValue(), advice));
  }

  // #1536: the four neutral reason codes replace the retired, health-revealing ones.
  private static final List<String> NEUTRAL_CODES =
      List.of("ADVICE_REQUESTED", "PLANNED_ABSENCE", "UNPLANNED_ABSENCE", "ASSIGNMENT_ENDED");

  @Test
  void listReasons_servesTheFourNeutralCodesForTenantPoliciesStillKeyedByRetiredCodes() {
    var reasons = caseHandoverService.listReasons(7L);

    assertThat(reasons)
        .extracting(CaseHandoverService.CaseHandoverReason::getCode)
        .containsExactlyElementsOf(NEUTRAL_CODES);
  }

  @Test
  void listReasons_withoutTenantServesOnlyNeutralBuiltInDefaults() {
    var reasons = caseHandoverService.listReasons(null);

    assertThat(reasons)
        .extracting(CaseHandoverService.CaseHandoverReason::getCode)
        .containsExactlyElementsOf(NEUTRAL_CODES);
    assertThat(caseHandoverService.listReasonPolicies())
        .allSatisfy(
            reason -> {
              assertThat(reason.getLabel()).doesNotContainIgnoringCase("ill");
              assertThat(
                      reason.getClientNotificationTemplates() == null
                          ? List.<String>of()
                          : reason.getClientNotificationTemplates().values())
                  .noneMatch(text -> text.matches("(?is).*(erkrankt|\\bill\\b|hastal|захвор).*"));
            });
  }

  @Test
  void listReasonPolicies_hidesRetiredRowsOfTheLegacyPolicyTable() {
    when(caseHandoverReasonPolicyRepository.findAllByOrderByDisplayOrderAscCodeAsc())
        .thenReturn(
            List.of(
                reasonPolicy("COUNSELLOR_IS_ILL", "Counsellor is ill", false, false, false, 40),
                reasonPolicy("UNPLANNED_ABSENCE", "Unplanned absence", false, true, true, 40)));

    var reasons = caseHandoverService.listReasonPolicies();

    assertThat(reasons)
        .extracting(CaseHandoverService.CaseHandoverReason::getCode)
        .containsExactly("UNPLANNED_ABSENCE");
  }

  @Test
  void requestAccess_storesTheNeutralCodeItWasGiven() {
    caseHandoverService.requestAccess(123L, "UNPLANNED_ABSENCE", "Cover.");

    var saved = ArgumentCaptor.forClass(CaseHandoverRequest.class);
    verify(caseHandoverRequestRepository, atLeastOnce()).save(saved.capture());
    assertEquals("UNPLANNED_ABSENCE", saved.getValue().getReasonCode());
    assertEquals(CaseHandoverRequest.AccessType.TAKEOVER, saved.getValue().getAccessType());
  }

  @ParameterizedTest
  @CsvSource({
    "COUNSELLOR_ON_HOLIDAY,PLANNED_ABSENCE",
    "COUNSELLOR_IS_ILL,UNPLANNED_ABSENCE",
    "COUNSELLOR_LEFT,ASSIGNMENT_ENDED"
  })
  void requestAccess_mapsARetiredCodeFromAnOlderClientToItsNeutralCode(
      String retired, String neutral) {
    caseHandoverService.requestAccess(123L, retired, "Cover.");

    var saved = ArgumentCaptor.forClass(CaseHandoverRequest.class);
    verify(caseHandoverRequestRepository, atLeastOnce()).save(saved.capture());
    assertEquals(neutral, saved.getValue().getReasonCode());
  }

  @Test
  void requestAccess_adviceRequestedStillGrantsTimeLimitedCoAccess() {
    when(caseHandoverPolicyCacheService.getEffective(7L))
        .thenReturn(tenantPolicies("Rat benötigt", 45, CaseHandoverConsentValue.NONE, Set.of()));

    caseHandoverService.requestAccess(123L, "ADVICE_REQUESTED", "Zweitmeinung");

    var saved = ArgumentCaptor.forClass(CaseHandoverRequest.class);
    verify(caseHandoverRequestRepository, atLeastOnce()).save(saved.capture());
    assertEquals("ADVICE_REQUESTED", saved.getValue().getReasonCode());
    assertEquals(CaseHandoverRequest.AccessType.CO_ACCESS, saved.getValue().getAccessType());
    assertEquals(45, saved.getValue().getMaxAccessDurationMinutes());
  }

  @Test
  void updateReasonPolicies_writesANeutralCodeToTheTenantPolicyKeyedByItsRetiredCode() {
    when(caseHandoverPolicyCacheService.updateEffective(eq(7L), any()))
        .thenAnswer(invocation -> invocation.getArgument(1));
    TenantContext.setCurrentTenant(7L);
    try {
      caseHandoverService.updateReasonPolicies(
          List.of(
              CaseHandoverService.CaseHandoverReason.builder()
                  .code("UNPLANNED_ABSENCE")
                  .label("Ausfall")
                  .enabled(true)
                  .accessAllowed(true)
                  .clientConsent(CaseHandoverConsentMode.NONE)
                  .build()));

      var written =
          ArgumentCaptor.forClass(
              de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverPolicies
                  .class);
      verify(caseHandoverPolicyCacheService).updateEffective(eq(7L), written.capture());
      assertEquals(
          "Ausfall",
          written
              .getValue()
              .getReasons()
              .get("COUNSELLOR_IS_ILL")
              .getLabels()
              .getValue()
              .get("de"));
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void getStatus_keepsAHistoricalRecordReadableWithoutItsHealthRevealingLabel() {
    var historical =
        CaseHandoverRequest.builder()
            .id(5L)
            .session(session)
            .requesterConsultant(requester)
            .previousConsultant(previous)
            .reasonCode("COUNSELLOR_IS_ILL")
            .reasonLabel("Counsellor is ill")
            .explanation("Cover")
            .status(CaseHandoverRequest.Status.GRANTED)
            .clientConsentRequired(false)
            .createdAt(LocalDateTime.of(2026, 8, 1, 10, 0))
            .build();
    when(caseHandoverRequestRepository.findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
            123L, "requester"))
        .thenReturn(List.of(historical));

    CaseHandoverStatus status = caseHandoverService.getStatus(123L);

    assertEquals("COUNSELLOR_IS_ILL", status.getReasonCode());
    assertEquals("Unplanned absence", status.getReasonLabel());
    assertEquals("TAKEOVER", status.getAccessType());
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
