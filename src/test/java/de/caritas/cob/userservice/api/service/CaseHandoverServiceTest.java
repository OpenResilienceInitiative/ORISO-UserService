package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
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
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.facade.SessionSupervisorFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
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
import de.caritas.cob.userservice.api.service.CaseHandoverService.CaseHandoverRecipient;
import de.caritas.cob.userservice.api.service.CaseHandoverService.CaseHandoverStatus;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.CaseHandoverEmailNotification;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionOwnershipService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverConsentValue;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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
  @Mock private SessionOwnershipService sessionOwnershipService;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private UserAccountService userAccountService;
  @Mock private EventNotificationService eventNotificationService;
  @Mock private CaseHandoverEmailNotification caseHandoverEmailNotification;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private CaseHandoverMatrixRepairService matrixRepairService;
  @Mock private MatrixSessionSystemMessageService matrixSessionSystemMessageService;
  @Mock private SessionSupervisorFacade sessionSupervisorFacade;
  @Mock private ConsultantService consultantService;
  @Mock private AuthenticatedUser authenticatedUser;
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
    // Revoking co-access gives the requester their member power level back first (#200).
    when(matrixSynapseService.setUserPowerLevel(anyString(), anyString(), eq(0), anyString()))
        .thenReturn(true);
    when(scheduledTaskClaimService.tryClaim(anyString(), any())).thenReturn(true);
    when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
  }

  @ParameterizedTest
  @EnumSource(
      value = CaseHandoverConsentValue.class,
      names = {"NONE", "OPT_OUT"})
  void requestAccess_standingAskParksCoAccessWithoutMatrixGrant(CaseHandoverConsentValue baseline) {
    session.setAlwaysAskBeforeAdditionalAccess(true);
    session.setMatrixRoomId("!case:example.org");
    when(caseHandoverPolicyCacheService.getEffective(7L))
        .thenReturn(tenantPolicies("Support", 180, baseline, Set.of()));
    var status = caseHandoverService.requestAccess(123L, "COUNSELLOR_ASKED_FOR_ADVICE", "support");
    assertEquals("PENDING_CLIENT_CONSENT", status.getStatus());
    assertEquals(CaseHandoverConsentMode.OPT_IN, status.getClientConsent());
    assertFalse(status.isCanViewContent());
    assertEquals(previous, session.getConsultant());
    verify(matrixSynapseService, never()).getRoomMembers(anyString());
  }

  @Test
  void requestAccess_standingOffDoesNotWeakenBaselineOptIn() {
    var status = caseHandoverService.requestAccess(123L, "COUNSELLOR_ASKED_FOR_ADVICE", "support");
    assertEquals("PENDING_CLIENT_CONSENT", status.getStatus());
    assertFalse(status.isCanViewContent());
  }

  @Test
  void consentPreference_disablingDoesNotRewriteAnAlreadyPendingRequest() {
    session.setAlwaysAskBeforeAdditionalAccess(true);
    asker.setTenantId(7L);
    TenantContext.setCurrentTenant(7L);
    try {
      var first = caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "cover");
      ArgumentCaptor<CaseHandoverRequest> capture =
          ArgumentCaptor.forClass(CaseHandoverRequest.class);
      verify(caseHandoverRequestRepository).save(capture.capture());
      var frozen = capture.getValue();
      when(caseHandoverRequestRepository
              .findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(123L, "requester"))
          .thenReturn(List.of(frozen));
      caseHandoverService.updateConsentPreference(123L, false);
      assertFalse(caseHandoverService.getConsentPreference(123L).alwaysAskBeforeAdditionalAccess());
      var stillPending = caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "cover");
      assertEquals(first.getStatus(), stillPending.getStatus());
      assertEquals(CaseHandoverConsentMode.OPT_IN, frozen.getClientConsent());
      assertEquals(previous, session.getConsultant());
      verify(caseHandoverRequestRepository).save(any());
      frozen.setId(99L);
      when(caseHandoverRequestRepository.findByIdAndSessionId(99L, 123L))
          .thenReturn(Optional.of(frozen));
      var approved = caseHandoverService.resolveClientConsent(123L, 99L, true);
      assertEquals("GRANTED", approved.getStatus());
      assertEquals(requester, session.getConsultant());
      assertFalse(caseHandoverService.getConsentPreference(123L).alwaysAskBeforeAdditionalAccess());
    } finally {
      TenantContext.clear();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "other-owner",
        "wrong-tenant",
        "technical-tenant",
        "missing-tenant",
        "user-tenant"
      })
  void consentPreference_rejectsReadAndSaveOutsideOwnerTenant(String scenario) {
    asker.setTenantId(7L);
    TenantContext.setCurrentTenant(7L);
    switch (scenario) {
      case "other-owner" -> {
        User other = new User();
        other.setUserId("other");
        other.setTenantId(7L);
        when(userAccountService.retrieveValidatedUser()).thenReturn(other);
      }
      case "wrong-tenant" -> TenantContext.setCurrentTenant(8L);
      case "technical-tenant" -> TenantContext.setCurrentTenant(0L);
      case "missing-tenant" -> TenantContext.clear();
      case "user-tenant" -> asker.setTenantId(8L);
      default -> throw new AssertionError(scenario);
    }
    try {
      assertThrows(ForbiddenException.class, () -> caseHandoverService.getConsentPreference(123L));
      assertThrows(
          ForbiddenException.class, () -> caseHandoverService.updateConsentPreference(123L, true));
      assertFalse(session.isAlwaysAskBeforeAdditionalAccess());
      verify(sessionRepository, never()).save(any());
      verify(sessionRepository, never()).updateAdditionalAccessPreference(anyLong(), anyBoolean());
    } finally {
      TenantContext.clear();
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = de.caritas.cob.userservice.api.model.ConversationType.class,
      names = {"LIVE_CHAT", "INTERNAL_GROUP", "SELF_HELP"})
  void consentPreference_rejectsOutOfScopeConversations(
      de.caritas.cob.userservice.api.model.ConversationType type) {
    asker.setTenantId(7L);
    TenantContext.setCurrentTenant(7L);
    session.setConversationType(type);
    try {
      assertThrows(ForbiddenException.class, () -> caseHandoverService.getConsentPreference(123L));
      assertThrows(
          ForbiddenException.class, () -> caseHandoverService.updateConsentPreference(123L, true));
      verify(sessionRepository, never()).save(any());
      verify(sessionRepository, never()).updateAdditionalAccessPreference(anyLong(), anyBoolean());
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void consentPreference_rejectsLegacyLiveChatWithNullModality() {
    asker.setTenantId(7L);
    TenantContext.setCurrentTenant(7L);
    session.setRegistrationType(Session.RegistrationType.ANONYMOUS);
    try {
      assertThrows(ForbiddenException.class, () -> caseHandoverService.getConsentPreference(123L));
      assertThrows(
          ForbiddenException.class, () -> caseHandoverService.updateConsentPreference(123L, true));
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void consentPreference_singleTenantInstallationStillUsesOwnerAndScopeChecks() {
    ReflectionTestUtils.setField(caseHandoverService, "preferenceMultitenancyEnabled", false);
    TenantContext.clear();
    try {
      assertTrue(
          caseHandoverService
              .updateConsentPreference(123L, true)
              .alwaysAskBeforeAdditionalAccess());
      assertTrue(caseHandoverService.getConsentPreference(123L).alwaysAskBeforeAdditionalAccess());
      User other = new User();
      other.setUserId("other");
      when(userAccountService.retrieveValidatedUser()).thenReturn(other);
      assertThrows(
          ForbiddenException.class, () -> caseHandoverService.updateConsentPreference(123L, false));
    } finally {
      ReflectionTestUtils.setField(caseHandoverService, "preferenceMultitenancyEnabled", true);
    }
  }

  @Test
  void consentPreference_ownerSavesAndReadsItWithoutResolvingAnExistingRequest() {
    asker.setTenantId(7L);
    TenantContext.setCurrentTenant(7L);
    when(sessionRepository.findByIdForUpdate(123L)).thenReturn(Optional.of(session));
    try {
      assertFalse(caseHandoverService.getConsentPreference(123L).alwaysAskBeforeAdditionalAccess());
      var saved = caseHandoverService.updateConsentPreference(123L, true);
      assertEquals(123L, saved.sessionId());
      assertTrue(caseHandoverService.getConsentPreference(123L).alwaysAskBeforeAdditionalAccess());
      assertEquals(previous, session.getConsultant());
      assertFalse(Boolean.TRUE.equals(session.getSupervisionOptedOut()));
      verify(sessionRepository).updateAdditionalAccessPreference(123L, true);
      verify(sessionRepository, never()).save(any());
      verify(caseHandoverRequestRepository, never()).save(any());
      verify(matrixSynapseService, never()).getRoomMembers(anyString());
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void requestAccess_standingAskPreventsTakeoverBeforeSpecificApproval() {
    session.setAlwaysAskBeforeAdditionalAccess(true);
    session.setMatrixRoomId("!case:example.org");

    var status = caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "cover");

    assertEquals("PENDING_CLIENT_CONSENT", status.getStatus());
    assertEquals(CaseHandoverConsentMode.OPT_IN, status.getClientConsent());
    assertFalse(status.isCanViewContent());
    assertEquals(previous, session.getConsultant());
    verify(sessionRepository, never()).save(any());
    verify(matrixSynapseService, never()).getRoomMembers(anyString());
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
        .setUserPowerLevel("!room:matrix", "@requester:matrix", 0, "previous-token");
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
        requestAccess(123L, "UNPLANNED_ABSENCE", "Urgent cover", 0L, operationId);

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
        () -> requestAccess(123L, "UNPLANNED_ABSENCE", "Changed explanation", 0L, operationId));
  }

  @Test
  void requestAccess_rejectsFreshOperationFromStaleOwnershipPeriod() {
    session.setOwnershipRevision(2L);

    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class,
        () ->
            requestAccess(
                123L,
                "UNPLANNED_ABSENCE",
                "Urgent cover",
                1L,
                UUID.fromString("f0378720-261a-439b-9bb5-f27510191544")));

    verify(caseHandoverRequestRepository, never()).save(any());
  }

  @Test
  void requestAccess_legacyPullRemainsAvailableAfterPreviousOwnership() {
    when(caseHandoverRequestRepository.findByPreviousConsultantId("requester"))
        .thenReturn(
            List.of(
                CaseHandoverRequest.builder()
                    .session(session)
                    .status(CaseHandoverRequest.Status.GRANTED)
                    .build()));
    CaseHandoverStatus status =
        caseHandoverService.requestAccess(123L, "UNPLANNED_ABSENCE", "Urgent cover");
    assertEquals("GRANTED", status.getStatus());
    verify(sessionOwnershipService).updateOwner(eq(session), eq(requester), any(), any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void requestAccess_partialOperationGuardFailsBeforeAnySideEffect(boolean includeRevision) {
    assertThrows(
        BadRequestException.class,
        () ->
            caseHandoverService.requestAccess(
                123L,
                "UNPLANNED_ABSENCE",
                "Urgent cover",
                includeRevision ? 0L : null,
                includeRevision ? null : UUID.randomUUID()));
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
                "UNPLANNED_ABSENCE",
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
                "UNPLANNED_ABSENCE",
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

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "false,NONE,NONE", "false,OPT_OUT,OPT_OUT", "false,OPT_IN,OPT_IN",
    "true,NONE,OPT_IN", "true,OPT_OUT,OPT_IN", "true,OPT_IN,OPT_IN"
  })
  void createOffer_freezesStandingPreferenceWithoutWeakeningReasonPolicy(
      boolean alwaysAsk, CaseHandoverConsentValue baseline, CaseHandoverConsentMode expected) {
    session.setConsultant(requester);
    session.setAlwaysAskBeforeAdditionalAccess(alwaysAsk);
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    when(consultantService.getConsultant("recipient")).thenReturn(Optional.of(recipient));
    var policies = defaultTenantPolicies();
    policies.getReasons().get("COUNSELLOR_IS_ILL").getClientConsent().setValue(baseline);
    when(caseHandoverPolicyCacheService.getEffective(7L)).thenReturn(policies);

    var result =
        caseHandoverService.createOffer(
            123L, "recipient", "UNPLANNED_ABSENCE", null, 0L, UUID.randomUUID());

    assertEquals(expected, result.getClientConsent());
    assertEquals(expected == CaseHandoverConsentMode.OPT_IN, result.isClientConsentRequired());
    assertEquals("PENDING_RECIPIENT_ACCEPTANCE", result.getStatus());
    verify(sessionOwnershipService, never()).updateOwner(any(), any(), any(), any());
    verify(caseHandoverEmailNotification, never()).ownershipGranted(any());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "ANONYMOUS,LIVE_CHAT,NONE",
    "ANONYMOUS,,NONE",
    "REGISTERED,,OPT_IN"
  })
  void createOffer_standingPreferenceUsesExactCurrentConversationScope(
      Session.RegistrationType registration, String modality, CaseHandoverConsentMode expected) {
    session.setConsultant(requester);
    session.setAlwaysAskBeforeAdditionalAccess(true);
    session.setRegistrationType(registration);
    session.setConversationType(
        modality == null
            ? null
            : de.caritas.cob.userservice.api.model.ConversationType.valueOf(modality));
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    when(consultantService.getConsultant("recipient")).thenReturn(Optional.of(recipient));
    var result =
        caseHandoverService.createOffer(
            123L, "recipient", "UNPLANNED_ABSENCE", null, 0L, UUID.randomUUID());
    assertEquals(expected, result.getClientConsent());
  }

  @ParameterizedTest
  @EnumSource(
      value = CaseHandoverConsentMode.class,
      names = {"NONE", "OPT_IN"})
  void resolveRecipientDecision_preservesFrozenConsentAfterPreferenceAndPolicyChange(
      CaseHandoverConsentMode frozen) {
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    CaseHandoverRequest request = pushRequest(recipient, UUID.randomUUID());
    request.setClientConsent(frozen);
    request.setClientConsentRequired(frozen == CaseHandoverConsentMode.OPT_IN);
    session.setConsultant(requester);
    session.setAlwaysAskBeforeAdditionalAccess(frozen == CaseHandoverConsentMode.NONE);
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(recipient);
    when(consultantService.getConsultant("recipient")).thenReturn(Optional.of(recipient));
    when(caseHandoverRequestRepository.findByIdAndSessionIdForUpdate(501L, 123L))
        .thenReturn(Optional.of(request));
    var policies = defaultTenantPolicies();
    policies
        .getReasons()
        .get("COUNSELLOR_IS_ILL")
        .getClientConsent()
        .setValue(
            frozen == CaseHandoverConsentMode.NONE
                ? CaseHandoverConsentValue.OPT_IN
                : CaseHandoverConsentValue.NONE);
    when(caseHandoverPolicyCacheService.getEffective(7L)).thenReturn(policies);

    var result = caseHandoverService.resolveRecipientDecision(123L, 501L, true);
    assertEquals(frozen, result.getClientConsent());
    assertEquals(
        frozen == CaseHandoverConsentMode.OPT_IN ? "PENDING_CLIENT_CONSENT" : "GRANTED",
        result.getStatus());
    caseHandoverService.resolveRecipientDecision(123L, 501L, true);
    if (frozen == CaseHandoverConsentMode.OPT_IN) {
      verify(sessionOwnershipService, never()).updateOwner(any(), any(), any(), any());
      verify(caseHandoverEmailNotification, times(1)).consentRequested(request);
      verify(caseHandoverEmailNotification, never()).ownershipGranted(any());
    } else {
      verify(sessionOwnershipService, times(1))
          .updateOwner(eq(session), eq(recipient), any(), any());
      verify(caseHandoverEmailNotification, times(1)).ownershipGranted(request);
    }
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
            123L, "recipient", "UNPLANNED_ABSENCE", null, 0L, operationId);

    assertEquals("PENDING_RECIPIENT_ACCEPTANCE", result.getStatus());
    assertEquals("PUSH", result.getDirection());
    assertEquals("requester", result.getInitiatorConsultantId());
    assertEquals("recipient", result.getRecipientConsultantId());
    assertEquals(operationId, result.getOperationId());
    assertEquals(requester, session.getConsultant());
    verify(sessionOwnershipService, never()).updateOwner(any(), any(), any(), any());
    verify(caseHandoverEmailNotification, never()).ownershipGranted(any(CaseHandoverRequest.class));
    verify(caseHandoverEmailNotification, never()).consentRequested(any(CaseHandoverRequest.class));
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
                "UNPLANNED_ABSENCE",
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
                "UNPLANNED_ABSENCE",
                null,
                0L,
                UUID.fromString("82703c1d-536d-4f2e-9d49-ad18742a46a3")));

    verify(caseHandoverRequestRepository, never()).save(any());
  }

  @Test
  void requestAccess_guardedPullPreservesAdviceCoAccessWithoutTransferringOwnership() {
    CaseHandoverStatus result =
        requestAccess(
            123L,
            "COUNSELLOR_ASKED_FOR_ADVICE",
            "Need advice, not ownership.",
            0L,
            UUID.fromString("40cd6572-b4b3-4b2f-b869-ac944142f110"));

    assertEquals("PENDING_CLIENT_CONSENT", result.getStatus());
    assertEquals("CO_ACCESS", result.getAccessType());
    verify(sessionOwnershipService, never()).updateOwner(any(), any(), any(), any());
    verify(caseHandoverEmailNotification).consentRequested(any(CaseHandoverRequest.class));
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
            123L, "recipient", "UNPLANNED_ABSENCE", null, 0L, operationId);

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
                123L, "other", "UNPLANNED_ABSENCE", null, 0L, operationId));
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
          "UNPLANNED_ABSENCE",
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
          "UNPLANNED_ABSENCE",
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
    verify(caseHandoverEmailNotification, never()).ownershipGranted(any(CaseHandoverRequest.class));
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
  void recipientAcceptancePreservesOptOutAndUsesTheCurrentMailProducerOnce() {
    Consultant recipient = eligibleConsultant("recipient", "Recipient");
    CaseHandoverRequest request = pushRequest(recipient, UUID.randomUUID());
    session.setConsultant(requester);
    var policies = defaultTenantPolicies();
    policies
        .getReasons()
        .get("COUNSELLOR_IS_ILL")
        .getClientConsent()
        .setValue(CaseHandoverConsentValue.OPT_OUT);
    when(caseHandoverPolicyCacheService.getEffective(7L)).thenReturn(policies);
    when(userAccountService.retrieveValidatedConsultant()).thenReturn(recipient);
    when(consultantService.getConsultant("recipient")).thenReturn(Optional.of(recipient));
    when(caseHandoverRequestRepository.findByIdAndSessionIdForUpdate(501L, 123L))
        .thenReturn(Optional.of(request));
    when(caseHandoverRequestRepository.findByIdAndSessionId(501L, 123L))
        .thenReturn(Optional.of(request));
    CaseHandoverStatus status = caseHandoverService.resolveRecipientDecision(123L, 501L, true);
    assertEquals("GRANTED_PENDING_CLIENT_OPTOUT", status.getStatus());
    assertEquals(recipient, session.getConsultant());
    assertEquals(1L, session.getOwnershipRevision());
    caseHandoverService.resolveRecipientDecision(123L, 501L, true);
    caseHandoverService.resolveClientConsent(123L, 501L, true);
    verify(caseHandoverEmailNotification, times(1)).ownershipGranted(request);
    verify(caseHandoverEmailNotification, times(1)).consentRequested(request);
    verify(sessionOwnershipService, times(1)).updateOwner(eq(session), eq(recipient), any(), any());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = CaseHandoverRequest.Status.class,
      names = {"PENDING_CLIENT_CONSENT", "GRANTED_PENDING_CLIENT_OPTOUT"})
  void getRequestStatus_adviceSeekerCanReadBothCurrentConsentPromptsWithoutStaffDetails(
      CaseHandoverRequest.Status pending) {
    CaseHandoverRequest request = pendingConsentRequest();
    request.setStatus(pending);
    when(authenticatedUser.isAdviceSeeker()).thenReturn(true);
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));
    CaseHandoverStatus status = caseHandoverService.getRequestStatus(123L, 88L);
    assertEquals(pending.name(), status.getStatus());
    assertNull(status.getReasonCode());
    assertNull(status.getReasonLabel());
    assertNull(status.getPolicyAuthority());
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
                123L, "recipient", "UNPLANNED_ABSENCE", null, 0L, operationId));
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

    requestAccess(123L, "UNPLANNED_ABSENCE", "Colleague is unavailable.");

    verify(caseHandoverEmailNotification, times(1))
        .ownershipGranted(any(CaseHandoverRequest.class));
    verify(caseHandoverEmailNotification, never()).consentRequested(any(CaseHandoverRequest.class));
  }

  @Test
  void requestAccess_sendsOneConsentEmailIntent_WhenClientConsentIsPending() {
    session.setMatrixRoomId("!handover-room:matrix");
    assignPersistedRequestId(82L);
    givenIllnessRequiresClientConsent();

    requestAccess(123L, "COUNSELLOR_IS_ILL", "Need temporary cover.");

    verify(caseHandoverEmailNotification, times(1))
        .consentRequested(any(CaseHandoverRequest.class));
    verify(caseHandoverEmailNotification, never()).ownershipGranted(any(CaseHandoverRequest.class));
  }

  @Test
  void resolveClientConsent_sendsOneGrantedEmailIntent_WhenClientApproves() throws Exception {
    UUID requesterId = prepareDeliverableHandoverMail();
    CaseHandoverRequest request = pendingTakeoverConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));

    caseHandoverService.resolveClientConsent(123L, 88L, true);

    verify(caseHandoverEmailNotification, times(1))
        .ownershipGranted(any(CaseHandoverRequest.class));
    verify(caseHandoverEmailNotification, never()).consentRequested(any(CaseHandoverRequest.class));
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
    var policies = defaultTenantPolicies();
    policies.getReasons().get("COUNSELLOR_IS_ILL").getAccessAllowed().setValue(false);
    when(caseHandoverPolicyCacheService.getEffective(7L)).thenReturn(policies);

    requestAccess(123L, "UNPLANNED_ABSENCE", "Needs cover.");

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
  void requestAccess_leavesBlankRoomDeliveryValidationToTheCurrentProducer() {
    session.setMatrixRoomId("  ");
    assignPersistedRequestId(83L);

    CaseHandoverStatus status =
        requestAccess(123L, "UNPLANNED_ABSENCE", "Colleague is unavailable.");

    assertEquals("GRANTED", status.getStatus());
    assertEquals(requester, session.getConsultant());
    verify(caseHandoverEmailNotification).ownershipGranted(any(CaseHandoverRequest.class));
  }

  @Test
  void requestAccess_failsClosedWhenSessionTenantIsMissing() {
    session.setTenantId(null);
    assertThrows(
        ServiceUnavailableException.class,
        () -> requestAccess(123L, "UNPLANNED_ABSENCE", "Colleague is unavailable."));
    verifyNoInteractions(caseHandoverEmailNotification);
    verify(caseHandoverRequestRepository, never()).save(any());
  }

  @Test
  void requestAccess_failsClosedWhenSessionTenantIsTechnical() {
    session.setTenantId(0L);
    assertThrows(
        ServiceUnavailableException.class,
        () -> requestAccess(123L, "UNPLANNED_ABSENCE", "Colleague is unavailable."));
    verifyNoInteractions(caseHandoverEmailNotification);
    verify(caseHandoverRequestRepository, never()).save(any());
  }

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
                        "COUNSELLOR_IS_ILL",
                        tenantPolicy(
                            "COUNSELLOR_IS_ILL",
                            "Other emergency",
                            de.caritas.cob.userservice.tenantadminservice.generated.web.model
                                .CaseHandoverConsentValue.NONE,
                            true,
                            false,
                            null))));

    requestAccess(123L, "UNPLANNED_ABSENCE", "Needs cover.");

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
            eq(session), anyString(), isNull(), isNull(), eq(88L), eq("OPT_IN")))
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

    requestAccess(123L, "COUNSELLOR_IS_ILL", "Client disclosed sensitive information.");

    verify(matrixSessionSystemMessageService)
        .postCaseHandoverGrantedMessage(
            eq(session),
            anyString(),
            description.capture(),
            org.mockito.ArgumentMatchers.any(
                MatrixSessionSystemMessageService.GrantedAccessMetadata.class));
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
  void requestAccess_persistsGrantedRequestConsentModeAndAccessType() {
    when(caseHandoverRequestRepository.save(any(CaseHandoverRequest.class)))
        .thenAnswer(
            invocation -> {
              CaseHandoverRequest saved = invocation.getArgument(0);
              saved.setId(42L);
              return saved;
            });
    caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Internal reason.");
    var metadata =
        ArgumentCaptor.forClass(MatrixSessionSystemMessageService.GrantedAccessMetadata.class);
    verify(matrixSessionSystemMessageService)
        .postCaseHandoverGrantedMessage(eq(session), anyString(), anyString(), metadata.capture());
    assertEquals(42L, metadata.getValue().requestId());
    assertEquals(CaseHandoverConsentMode.NONE, metadata.getValue().clientConsent());
    assertEquals(CaseHandoverRequest.AccessType.TAKEOVER, metadata.getValue().accessType());
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
                "Requesting Counsellor hat deinen Fall übernommen und führt deine Beratung ab jetzt"
                    + " weiter."),
            org.mockito.ArgumentMatchers.any(
                MatrixSessionSystemMessageService.GrantedAccessMetadata.class));
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

    requestAccess(123L, reasonCode, "Client disclosed sensitive information.");

    verify(matrixSessionSystemMessageService)
        .postCaseHandoverGrantedMessage(
            eq(session),
            anyString(),
            description.capture(),
            org.mockito.ArgumentMatchers.any(
                MatrixSessionSystemMessageService.GrantedAccessMetadata.class));
    assertEquals(
        "Requesting Counsellor hat deinen Fall übernommen und führt deine Beratung ab jetzt"
            + " weiter.",
        description.getValue());
  }

  /** The reason stays — it is a configured label, not free text — and moves into params. */
  @Test
  void requestAccess_carriesRequesterAndReasonAsParams() {
    requestAccess(123L, "COUNSELLOR_IS_ILL", "Illness cover.");

    verify(eventNotificationService, atLeastOnce())
        .buildCaseHandoverParams(any(), anyString(), eq("UNPLANNED_ABSENCE"), any(), any());
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

    CaseHandoverStatus status =
        caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Colleague is unavailable.");

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
        () ->
            caseHandoverService.requestAccess(
                123L, "COUNSELLOR_IS_ILL", "Colleague is unavailable."));

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

    caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Colleague is unavailable.");

    verify(matrixSessionSystemMessageService)
        .postCaseHandoverGrantedMessage(
            org.mockito.ArgumentMatchers.eq(session),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.contains("deinen Fall übernommen"),
            org.mockito.ArgumentMatchers.any(
                MatrixSessionSystemMessageService.GrantedAccessMetadata.class));
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void requestAccess_deniesAndKeepsContentLocked_WhenPolicyDoesNotAllowReason(boolean alwaysAsk) {
    session.setAlwaysAskBeforeAdditionalAccess(alwaysAsk);
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

    CaseHandoverStatus status = requestAccess(123L, "UNPLANNED_ABSENCE", "Needs cover again.");

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

    session.getUser().setAvatarId("fox");
    session
        .getConsultant()
        .setAvatarKind(de.caritas.cob.userservice.api.model.ConsultantAvatarKind.ICON);
    session.getConsultant().setAvatarId("magpie");

    var response = caseHandoverService.searchCandidates("asker", 0, 15, false);

    assertEquals(1, response.getTotal());
    assertEquals(1, response.getCount());
    var candidate = response.getSessions().get(0);
    assertEquals(123L, candidate.getSession().getId());
    assertEquals("asker", candidate.getUser().getUsername());
    assertNull(candidate.getUser().getSessionData());
    assertEquals("fox", candidate.getUser().getAvatarId());
    assertEquals("ICON", candidate.getConsultant().getAvatarKind());
    assertEquals("magpie", candidate.getConsultant().getAvatarId());
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
    verify(sessionOwnershipService, never()).updateOwner(any(), any(), any(), any());
    verify(sessionRepository, never()).save(session);
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
  void resolveClientConsent_legacyPendingAdvicePreservesCurrentCoAccess() {
    CaseHandoverRequest request = pendingConsentRequest();
    request.setReasonCode("COUNSELLOR_ASKED_FOR_ADVICE");
    request.setReasonLabel("Advice");
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));

    CaseHandoverStatus status = caseHandoverService.resolveClientConsent(123L, 88L, true);

    assertEquals("GRANTED", status.getStatus());
    assertEquals("CO_ACCESS", status.getAccessType());
    assertEquals(CaseHandoverRequest.Status.GRANTED, request.getStatus());
    assertEquals(previous, session.getConsultant());
    verify(sessionOwnershipService, never()).updateOwner(any(), any(), any(), any());
  }

  @Test
  void resolveClientConsent_legacyPendingPullWithoutOwnershipPeriodRemainsDecidable() {
    CaseHandoverRequest request = pendingConsentRequest();
    request.setExpectedOwnershipRevision(null);
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));

    CaseHandoverStatus status = caseHandoverService.resolveClientConsent(123L, 88L, true);
    assertEquals("GRANTED", status.getStatus());
    assertEquals("CO_ACCESS", status.getAccessType());

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
            description.capture(),
            org.mockito.ArgumentMatchers.any(
                MatrixSessionSystemMessageService.GrantedAccessMetadata.class));
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
  void searchCandidates_usesTheTopicsOfTheSessionsCentreOnly() {
    // #1264: topic 5 is stored for centre 10 only, so centre 20's topic-5 case is another
    // department; a legacy row without a centre (topic 6) still counts for every centre.
    givenTwoRequesterCentres();
    requester.setConsultantTopics(
        Set.of(requesterTopic(10L, 5L), requesterTopic(null, 6L), requesterTopic(20L, 7L)));
    when(sessionRepository.findByAgencyIdInAndConsultantNotAndStatusInOrderByUpdateDateDesc(
            any(), eq(requester), eq(List.of(SessionStatus.IN_PROGRESS, SessionStatus.DONE))))
        .thenReturn(
            List.of(
                candidateSession(221L, 10L, 5L, false),
                candidateSession(222L, 20L, 5L, false),
                candidateSession(223L, 20L, 6L, false),
                candidateSession(224L, 20L, 7L, false),
                candidateSession(225L, 10L, 7L, false)));

    var response = caseHandoverService.searchCandidates("asker", 0, 15, false);

    assertEquals(
        List.of(221L, 223L, 224L),
        response.getSessions().stream().map(dto -> dto.getSession().getId()).toList());
  }

  private void givenTwoRequesterCentres() {
    ConsultantAgency firstAgency = requester.getConsultantAgencies().iterator().next();
    firstAgency.setId(1L);
    ConsultantAgency secondAgency = new ConsultantAgency();
    secondAgency.setId(2L);
    secondAgency.setAgencyId(20L);
    secondAgency.setConsultant(requester);
    requester.setConsultantAgencies(Set.of(firstAgency, secondAgency));
  }

  private ConsultantTopic requesterTopic(Long agencyId, long topicId) {
    ConsultantTopic topic = new ConsultantTopic();
    topic.setConsultant(requester);
    topic.setAgencyId(agencyId);
    topic.setTopicId(topicId);
    return topic;
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

  // --- FE#1262: the picker may only offer colleagues the offer itself would accept ---

  private Consultant departmentColleague(String id, String displayName, Long... topicIds) {
    Consultant colleague = eligibleConsultant(id, displayName);
    Set<ConsultantTopic> topics = new HashSet<>();
    for (Long topicId : topicIds) {
      ConsultantTopic topic = new ConsultantTopic();
      topic.setConsultant(colleague);
      topic.setTopicId(topicId);
      topics.add(topic);
    }
    colleague.setConsultantTopics(topics);
    return colleague;
  }

  private void givenAgencyRoster(Consultant... consultants) {
    List<ConsultantAgency> rows = new ArrayList<>();
    for (Consultant colleague : consultants) {
      ConsultantAgency row = new ConsultantAgency();
      row.setAgencyId(10L);
      row.setConsultant(colleague);
      rows.add(row);
    }
    when(consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNullOrderByConsultantFirstNameAsc(
            10L))
        .thenReturn(rows);
  }

  private void givenOwnerAsksForRecipients() {
    ReflectionTestUtils.setField(caseHandoverService, "topicsEnabled", true);
    session.setConsultant(requester);
    session.setMainTopicId(5L);
  }

  @Test
  void listEligibleRecipients_keepsOnlyColleaguesWhoShareTheSessionTopic() {
    givenOwnerAsksForRecipients();
    givenAgencyRoster(
        requester,
        departmentColleague("same-topic", "Jonas Lehmann", 5L),
        departmentColleague("other-topic", "Ayse Demir", 9L),
        departmentColleague("no-topic", "Topicless Colleague"));

    List<CaseHandoverRecipient> recipients = caseHandoverService.listEligibleRecipients(123L);

    assertEquals(1, recipients.size());
    assertEquals("same-topic", recipients.get(0).getConsultantId());
    assertEquals("Jonas Lehmann", recipients.get(0).getDisplayName());
  }

  @Test
  void listEligibleRecipients_matchesASecondarySessionTopicNotOnlyTheMainOne() {
    givenOwnerAsksForRecipients();
    SessionTopic secondary = new SessionTopic();
    secondary.setTopicId(9L);
    session.setSessionTopics(List.of(secondary));
    givenAgencyRoster(requester, departmentColleague("secondary", "Ayse Demir", 9L));

    List<CaseHandoverRecipient> recipients = caseHandoverService.listEligibleRecipients(123L);

    assertEquals(1, recipients.size());
    assertEquals("secondary", recipients.get(0).getConsultantId());
  }

  @Test
  void listEligibleRecipients_leavesOutAbsentColleaguesAndForeignTenants() {
    givenOwnerAsksForRecipients();
    Consultant absent = departmentColleague("absent", "Absent Colleague", 5L);
    absent.setAbsent(true);
    Consultant foreign = departmentColleague("foreign", "Foreign Tenant", 5L);
    foreign.setTenantId(8L);
    givenAgencyRoster(requester, absent, foreign);

    assertTrue(caseHandoverService.listEligibleRecipients(123L).isEmpty());
  }

  @Test
  void listEligibleRecipients_leavesOutAPreviousOwnerBecauseReclaimIsRejected() {
    givenOwnerAsksForRecipients();
    Consultant returning = departmentColleague("returning", "Returning Colleague", 5L);
    givenAgencyRoster(requester, returning);
    CaseHandoverRequest granted = grantedRequest(requester);
    granted.setPreviousConsultant(returning);
    when(caseHandoverRequestRepository.findBySessionId(123L)).thenReturn(List.of(granted));

    assertTrue(caseHandoverService.listEligibleRecipients(123L).isEmpty());
  }

  @Test
  void listEligibleRecipients_rejectsEveryoneButTheActiveOwner() {
    ReflectionTestUtils.setField(caseHandoverService, "topicsEnabled", true);
    session.setConsultant(previous);

    assertThrows(ForbiddenException.class, () -> caseHandoverService.listEligibleRecipients(123L));
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
        .postCaseHandoverGrantedMessage(
            eq(session),
            advisorName.capture(),
            description.capture(),
            org.mockito.ArgumentMatchers.any(
                MatrixSessionSystemMessageService.GrantedAccessMetadata.class));

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
        .postCaseHandoverGrantedMessage(
            eq(session),
            eq("Frau M."),
            anyString(),
            org.mockito.ArgumentMatchers.any(
                MatrixSessionSystemMessageService.GrantedAccessMetadata.class));
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
        .reasonCode("UNPLANNED_ABSENCE")
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
        .initiatorConsultant(consultant)
        .previousConsultant(previous)
        .direction(CaseHandoverRequest.Direction.PULL)
        .expectedOwnershipRevision(0L)
        .operationId(UUID.fromString("b975d19f-43b8-44eb-b845-3992e57ab06e"))
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

  // #200: co-access is read-only. Session rooms use events_default 0, so for the lifetime of the
  // grant the requester's power level drops to -1 and Synapse refuses their messages.

  private void givenAdviceIsGrantedWithoutWaitingForTheClient() {
    when(caseHandoverPolicyCacheService.getEffective(7L))
        .thenReturn(
            tenantPolicies(
                "Rat benötigt",
                180,
                de.caritas.cob.userservice.tenantadminservice.generated.web.model
                    .CaseHandoverConsentValue.OPT_OUT,
                Set.of()));
  }

  private void givenARoomTheRequesterCanJoin() {
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
  }

  @Test
  void requestAccess_makesAdviceCoAccessReadOnlyInTheMatrixRoom() {
    givenAdviceIsGrantedWithoutWaitingForTheClient();
    givenARoomTheRequesterCanJoin();
    when(matrixSynapseService.setUserPowerLevel(
            "!room:matrix", "@requester:matrix", -1, "previous-token"))
        .thenReturn(true);

    caseHandoverService.requestAccess(123L, "COUNSELLOR_ASKED_FOR_ADVICE", "Zweitmeinung");

    verify(matrixSynapseService)
        .setUserPowerLevel("!room:matrix", "@requester:matrix", -1, "previous-token");
  }

  @Test
  void requestAccess_refusesAdviceCoAccessThatCannotBeMadeReadOnly() {
    givenAdviceIsGrantedWithoutWaitingForTheClient();
    givenARoomTheRequesterCanJoin();

    assertThrows(
        InternalServerErrorException.class,
        () ->
            caseHandoverService.requestAccess(123L, "COUNSELLOR_ASKED_FOR_ADVICE", "Zweitmeinung"));

    verify(caseHandoverRequestRepository, never()).save(any());
  }

  @Test
  void requestAccess_givesTheTakeoverRecipientTheOwnersPowerLevel() {
    givenARoomTheRequesterCanJoin();
    when(matrixSynapseService.setUserPowerLevel(
            "!room:matrix", "@requester:matrix", 100, "previous-token"))
        .thenReturn(true);

    caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Colleague is unavailable.");

    verify(matrixSynapseService)
        .setUserPowerLevel("!room:matrix", "@requester:matrix", 100, "previous-token");
    verify(matrixSynapseService, never())
        .setUserPowerLevel(anyString(), anyString(), eq(-1), anyString());
  }

  /** An absence cover must never fail over Matrix room rights: at level 0 the owner can post. */
  @Test
  void requestAccess_completesTheTakeoverWhenTheOwnersPowerLevelCannotBeSet() {
    givenARoomTheRequesterCanJoin();

    var status =
        caseHandoverService.requestAccess(123L, "COUNSELLOR_IS_ILL", "Colleague is unavailable.");

    assertEquals("GRANTED", status.getStatus());
    assertEquals(requester, session.getConsultant());
  }

  @Test
  void resolveClientConsent_givesTheApprovedTakeoverRecipientTheOwnersPowerLevel() {
    CaseHandoverRequest request = pendingTakeoverConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));
    givenARoomTheRequesterCanJoin();
    when(matrixSynapseService.setUserPowerLevel(
            "!room:matrix", "@requester:matrix", 100, "previous-token"))
        .thenReturn(true);

    caseHandoverService.resolveClientConsent(123L, 88L, true);

    verify(matrixSynapseService)
        .setUserPowerLevel("!room:matrix", "@requester:matrix", 100, "previous-token");
  }

  @Test
  void resolveClientConsent_makesApprovedAdviceCoAccessReadOnly() {
    CaseHandoverRequest request = pendingConsentRequest();
    when(caseHandoverRequestRepository.findByIdAndSessionId(88L, 123L))
        .thenReturn(Optional.of(request));
    givenARoomTheRequesterCanJoin();
    when(matrixSynapseService.setUserPowerLevel(
            "!room:matrix", "@requester:matrix", -1, "previous-token"))
        .thenReturn(true);

    caseHandoverService.resolveClientConsent(123L, 88L, true);

    verify(matrixSynapseService)
        .setUserPowerLevel("!room:matrix", "@requester:matrix", -1, "previous-token");
  }

  private CaseHandoverRequest expiredAdviceGrantOfAStandingMember() {
    CaseHandoverRequest request = grantedAdviceRequest();
    request.setMatrixMembershipAdded(false);
    request.setExpiresAt(LocalDateTime.of(2026, 8, 16, 10, 0));
    session.setMatrixRoomId("!room:matrix");
    requester.setMatrixUserId("@requester:matrix");
    previous.setMatrixUserId("@previous:matrix");
    when(matrixSynapseService.loginAsUserAccessToken("@previous:matrix"))
        .thenReturn("previous-token");
    when(caseHandoverRequestRepository.findByStatusAndAccessTypeAndExpiresAtLessThanEqual(
            CaseHandoverRequest.Status.GRANTED,
            CaseHandoverRequest.AccessType.CO_ACCESS,
            LocalDateTime.of(2026, 8, 16, 10, 0)))
        .thenReturn(List.of(request));
    when(caseHandoverRequestRepository.findByIdForUpdate(request.getId()))
        .thenReturn(Optional.of(request));
    return request;
  }

  @Test
  void expireCoAccess_givesAStandingMemberTheirPowerLevelBack() {
    CaseHandoverRequest request = expiredAdviceGrantOfAStandingMember();
    when(matrixSynapseService.setUserPowerLevel(
            "!room:matrix", "@requester:matrix", 0, "previous-token"))
        .thenReturn(true);

    assertEquals(1, caseHandoverService.expireCoAccess());

    assertEquals(CaseHandoverRequest.Status.EXPIRED, request.getStatus());
    verify(matrixSynapseService)
        .setUserPowerLevel("!room:matrix", "@requester:matrix", 0, "previous-token");
    verifyNoMatrixRemoval();
  }

  @Test
  void expireCoAccess_keepsTheGrantForTheNextSweepWhenThePowerLevelCannotBeRestored() {
    CaseHandoverRequest request = expiredAdviceGrantOfAStandingMember();
    when(matrixSynapseService.setUserPowerLevel(
            "!room:matrix", "@requester:matrix", 0, "previous-token"))
        .thenReturn(false);

    assertEquals(0, caseHandoverService.expireCoAccess());

    assertEquals(CaseHandoverRequest.Status.GRANTED, request.getStatus());
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

  private void givenIllnessRequiresClientConsent() {
    var policies = defaultTenantPolicies();
    policies
        .getReasons()
        .get("COUNSELLOR_IS_ILL")
        .getClientConsent()
        .setValue(CaseHandoverConsentValue.OPT_IN);
    when(caseHandoverPolicyCacheService.getEffective(7L)).thenReturn(policies);
  }
}
