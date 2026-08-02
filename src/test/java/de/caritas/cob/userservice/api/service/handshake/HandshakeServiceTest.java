package de.caritas.cob.userservice.api.service.handshake;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.admin.service.admin.GlobalSupportAdminUserService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.GoneException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.HandshakeAuditEvent;
import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent;
import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.model.HandshakeSession.HandshakeStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeAuditEventRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeOutboxEventRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeSessionRepository;
import de.caritas.cob.userservice.api.port.out.SupportAccessSessionRepository;
import de.caritas.cob.userservice.api.service.handshake.HandshakeService.InitiateHandshakeRequest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class HandshakeServiceTest {

  private static final String SUPPORT_ADMIN_ID = "support-admin-1";
  private static final String CONSULTANT_ID = "consultant-1";
  private static final String HANDSHAKE_ID = "handshake-1";
  private static final Long AGENCY_ID = 7L;

  @Mock private HandshakeSessionRepository handshakeSessionRepository;
  @Mock private HandshakeAuditEventRepository handshakeAuditEventRepository;
  @Mock private HandshakeOutboxEventRepository handshakeOutboxEventRepository;
  @Mock private KeycloakAuthClient keycloakAuthClient;
  @Mock private GlobalSupportAdminUserService globalSupportAdminUserService;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private SupportAccessSessionRepository supportAccessSessionRepository;

  private HandshakeService handshakeService;

  @BeforeEach
  void setUp() {
    handshakeService =
        new HandshakeService(
            handshakeSessionRepository,
            handshakeAuditEventRepository,
            keycloakAuthClient,
            handshakeOutboxEventRepository,
            globalSupportAdminUserService,
            consultantRepository,
            consultantAgencyRepository,
            supportAccessSessionRepository);
    ReflectionTestUtils.setField(handshakeService, "ttlSeconds", 300L);
    ReflectionTestUtils.setField(handshakeService, "maxConfirmAttempts", 5);
    ReflectionTestUtils.setField(handshakeService, "auditRetentionMonths", 12L);
    ReflectionTestUtils.setField(handshakeService, "supportAccessEnabled", true);
  }

  // --- initiate ---

  @Test
  void initiate_Should_CreatePendingSessionScopedToConsultantAndAgency() {
    givenValidInitiation();
    when(handshakeSessionRepository.save(any(HandshakeSession.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var item = handshakeService.initiate(supportAdmin(), supportRequest());

    var captor = ArgumentCaptor.forClass(HandshakeSession.class);
    verify(handshakeSessionRepository).save(captor.capture());
    var saved = captor.getValue();
    assertThat(saved.getStatus()).isEqualTo(HandshakeStatus.PENDING);
    assertThat(saved.getInitiatorId()).isEqualTo(SUPPORT_ADMIN_ID);
    assertThat(saved.getCounterpartId()).isEqualTo(CONSULTANT_ID);
    assertThat(saved.getAgencyId()).isEqualTo(AGENCY_ID);
    // Tenant comes from the persisted consultant, never from the request.
    assertThat(saved.getTenantId()).isEqualTo(42L);
    assertThat(saved.getExpiryDate())
        .isAfter(nowInUtc().plusSeconds(200))
        .isBefore(nowInUtc().plusSeconds(400));
    assertThat(item.getStatus()).isEqualTo("PENDING");
    verify(globalSupportAdminUserService).requireOperationalSupportAdmin();
    verify(handshakeAuditEventRepository).save(any());
  }

  @Test
  void initiate_Should_Refuse_When_TheFeatureFlagIsOff() {
    ReflectionTestUtils.setField(handshakeService, "supportAccessEnabled", false);

    assertThatThrownBy(() -> handshakeService.initiate(supportAdmin(), supportRequest()))
        .isInstanceOf(ForbiddenException.class);
    verify(keycloakAuthClient, never()).verifyWithOtp(anyString(), anyString(), anyString());
  }

  @Test
  void initiate_Should_Forbid_When_InitiatorLacksPurposeRole() {
    assertThatThrownBy(() -> handshakeService.initiate(consultant(), supportRequest()))
        .isInstanceOf(ForbiddenException.class);
    verify(handshakeSessionRepository, never()).save(any());
  }

  @Test
  void initiate_Should_Forbid_When_FreshCredentialCheckFails() {
    when(keycloakAuthClient.verifyWithOtp(anyString(), anyString(), anyString())).thenReturn(false);

    assertThatThrownBy(() -> handshakeService.initiate(supportAdmin(), supportRequest()))
        .isInstanceOf(ForbiddenException.class);
    verify(handshakeSessionRepository, never()).save(any());
  }

  @Test
  void initiate_Should_Reject_When_ConsultantIsNotAssignedToTheRequestedAgency() {
    when(keycloakAuthClient.verifyWithOtp("support.admin", "secret", "123456")).thenReturn(true);
    when(consultantRepository.findActiveByIdForUpdate(CONSULTANT_ID))
        .thenReturn(Optional.of(consultantEntity()));
    when(consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            CONSULTANT_ID, AGENCY_ID))
        .thenReturn(false);

    assertThatThrownBy(() -> handshakeService.initiate(supportAdmin(), supportRequest()))
        .isInstanceOf(BadRequestException.class);
    verify(handshakeSessionRepository, never()).save(any());
  }

  @Test
  void initiate_Should_Conflict_When_ARequestForThatPairAndAgencyIsAlreadyOpen() {
    when(keycloakAuthClient.verifyWithOtp("support.admin", "secret", "123456")).thenReturn(true);
    when(consultantRepository.findActiveByIdForUpdate(CONSULTANT_ID))
        .thenReturn(Optional.of(consultantEntity()));
    when(consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            CONSULTANT_ID, AGENCY_ID))
        .thenReturn(true);
    when(handshakeSessionRepository
            .existsByInitiatorIdAndCounterpartIdAndAgencyIdAndPurposeAndStatusIn(
                anyString(), anyString(), anyLong(), any(), any()))
        .thenReturn(true);

    assertThatThrownBy(() -> handshakeService.initiate(supportAdmin(), supportRequest()))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void initiate_Should_Conflict_When_ASessionForThatPairIsAlreadyRunning() {
    givenValidInitiation();
    when(supportAccessSessionRepository.existsBySupportAdminIdAndConsultantIdAndStatusIn(
            anyString(), anyString(), any()))
        .thenReturn(true);

    assertThatThrownBy(() -> handshakeService.initiate(supportAdmin(), supportRequest()))
        .isInstanceOf(ConflictException.class);
    verify(handshakeSessionRepository, never()).save(any());
  }

  @Test
  void initiate_Should_Reject_SelfHandshake() {
    var request = supportRequest();
    request.setConsultantId(SUPPORT_ADMIN_ID);

    assertThatThrownBy(() -> handshakeService.initiate(supportAdmin(), request))
        .isInstanceOf(BadRequestException.class);
  }

  // --- confirm ---

  @Test
  void confirm_Should_MoveToConfirmedAndEnqueueExactlyOneProvisioningJob() {
    var session = pendingSession();
    when(handshakeSessionRepository.findById(HANDSHAKE_ID)).thenReturn(Optional.of(session));
    when(keycloakAuthClient.verifyIgnoringOtp("consultant.user", "secret")).thenReturn(true);
    // The conditional update is what changes the row; mirror that so the re-read sees CONFIRMED.
    when(handshakeSessionRepository.confirmIfStillPending(anyString(), any()))
        .thenAnswer(
            invocation -> {
              session.setStatus(HandshakeStatus.CONFIRMED);
              return 1;
            });

    var item = handshakeService.confirm(consultant(), HANDSHAKE_ID, "secret");

    var captor = ArgumentCaptor.forClass(HandshakeOutboxEvent.class);
    verify(handshakeOutboxEventRepository).save(captor.capture());
    assertThat(captor.getValue().getEventType()).isEqualTo(SupportAccessJob.PROVISION_ROOM.name());
    assertThat(captor.getValue().getAggregateId()).isEqualTo(HANDSHAKE_ID);
    assertThat(item.getStatus()).isEqualTo("CONFIRMED");
  }

  @Test
  void confirm_Should_NotCreateJob_When_TheConditionalUpdateLost() {
    var session = pendingSession();
    when(handshakeSessionRepository.findById(HANDSHAKE_ID)).thenReturn(Optional.of(session));
    when(keycloakAuthClient.verifyIgnoringOtp("consultant.user", "secret")).thenReturn(true);
    // Someone else confirmed between our read and our write.
    when(handshakeSessionRepository.confirmIfStillPending(anyString(), any())).thenReturn(0);

    assertThatThrownBy(() -> handshakeService.confirm(consultant(), HANDSHAKE_ID, "secret"))
        .isInstanceOf(ConflictException.class);
    verify(handshakeOutboxEventRepository, never()).save(any());
  }

  @Test
  void confirm_Should_Forbid_When_CallerIsNotTheAddressedCounterpart() {
    var session = pendingSession();
    session.setCounterpartId("somebody-else");
    when(handshakeSessionRepository.findById(HANDSHAKE_ID)).thenReturn(Optional.of(session));

    assertThatThrownBy(() -> handshakeService.confirm(consultant(), HANDSHAKE_ID, "secret"))
        .isInstanceOf(ForbiddenException.class);
    verify(keycloakAuthClient, never()).verifyIgnoringOtp(anyString(), anyString());
  }

  @Test
  void confirm_Should_Forbid_When_PasswordCheckFails() {
    var session = pendingSession();
    when(handshakeSessionRepository.findById(HANDSHAKE_ID)).thenReturn(Optional.of(session));
    when(keycloakAuthClient.verifyIgnoringOtp("consultant.user", "wrong")).thenReturn(false);

    assertThatThrownBy(() -> handshakeService.confirm(consultant(), HANDSHAKE_ID, "wrong"))
        .isInstanceOf(ForbiddenException.class);
    assertThat(session.getConfirmAttempts()).isEqualTo(1);
    verify(handshakeSessionRepository, never()).delete(any());
  }

  @Test
  void confirm_Should_RemoveTheRow_OnTheFinalFailedAttempt() {
    var session = pendingSession();
    session.setConfirmAttempts(4);
    when(handshakeSessionRepository.findById(HANDSHAKE_ID)).thenReturn(Optional.of(session));
    when(keycloakAuthClient.verifyIgnoringOtp("consultant.user", "wrong")).thenReturn(false);

    assertThatThrownBy(() -> handshakeService.confirm(consultant(), HANDSHAKE_ID, "wrong"))
        .isInstanceOf(ForbiddenException.class);
    // Terminal: with no operational row left the request can never be confirmed afterwards.
    verify(handshakeSessionRepository).delete(session);
  }

  @Test
  void confirm_Should_ReportGoneAndLeaveNoRow_When_TheWindowHasClosed() {
    var session = pendingSession();
    session.setExpiryDate(nowInUtc().minusSeconds(1));
    when(handshakeSessionRepository.findById(HANDSHAKE_ID)).thenReturn(Optional.of(session));

    assertThatThrownBy(() -> handshakeService.confirm(consultant(), HANDSHAKE_ID, "secret"))
        .isInstanceOf(GoneException.class);
    verify(handshakeSessionRepository).delete(session);
    assertThat(auditedEvents()).containsExactly("SESSION_NOT_ESTABLISHED");
  }

  @Test
  void confirm_Should_Conflict_When_TheRequestWasAlreadyDecided() {
    var session = pendingSession();
    session.setStatus(HandshakeStatus.DECLINED);
    when(handshakeSessionRepository.findById(HANDSHAKE_ID)).thenReturn(Optional.of(session));

    assertThatThrownBy(() -> handshakeService.confirm(consultant(), HANDSHAKE_ID, "secret"))
        .isInstanceOf(ConflictException.class);
  }

  // --- decline ---

  @Test
  void decline_Should_RecordTheRefusalWithoutCreatingAnyJob() {
    var session = pendingSession();
    when(handshakeSessionRepository.findById(HANDSHAKE_ID)).thenReturn(Optional.of(session));
    when(handshakeSessionRepository.saveAndFlush(any(HandshakeSession.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var item = handshakeService.decline(consultant(), HANDSHAKE_ID);

    assertThat(item.getStatus()).isEqualTo("DECLINED");
    assertThat(auditedEvents()).containsExactly("DECLINED");
    verify(handshakeOutboxEventRepository, never()).save(any());
  }

  // --- sweeps ---

  @Test
  void sweepExpired_Should_LeaveExactlyOneAuditEntryAndNoOperationalRow() {
    var session = pendingSession();
    session.setExpiryDate(nowInUtc().minusMinutes(10));
    when(handshakeSessionRepository.findAllByStatusAndExpiryDateBefore(
            any(HandshakeStatus.class), any()))
        .thenReturn(List.of(session));

    handshakeService.sweepExpired();

    verify(handshakeSessionRepository).delete(session);
    assertThat(auditedEvents()).containsExactly("SESSION_NOT_ESTABLISHED");
  }

  @Test
  void purgeOldAuditEvents_Should_DeleteEntriesOlderThanRetention() {
    handshakeService.purgeOldAuditEvents();

    var captor = ArgumentCaptor.forClass(java.time.LocalDateTime.class);
    verify(handshakeAuditEventRepository).deleteAllByCreateDateBefore(captor.capture());
    assertThat(captor.getValue()).isBefore(nowInUtc().minusMonths(11));
  }

  // --- helpers ---

  private void givenValidInitiation() {
    lenient()
        .when(keycloakAuthClient.verifyWithOtp("support.admin", "secret", "123456"))
        .thenReturn(true);
    lenient()
        .when(consultantRepository.findActiveByIdForUpdate(CONSULTANT_ID))
        .thenReturn(Optional.of(consultantEntity()));
    lenient()
        .when(
            consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
                CONSULTANT_ID, AGENCY_ID))
        .thenReturn(true);
  }

  private List<String> auditedEvents() {
    var captor = ArgumentCaptor.forClass(HandshakeAuditEvent.class);
    verify(handshakeAuditEventRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
    return captor.getAllValues().stream().map(HandshakeAuditEvent::getEvent).toList();
  }

  private Consultant consultantEntity() {
    var consultant = new Consultant();
    consultant.setId(CONSULTANT_ID);
    consultant.setTenantId(42L);
    return consultant;
  }

  private HandshakeSession pendingSession() {
    return HandshakeSession.builder()
        .id(HANDSHAKE_ID)
        .purpose(HandshakePurpose.SUPPORT_ACCESS)
        .initiatorId(SUPPORT_ADMIN_ID)
        .counterpartId(CONSULTANT_ID)
        .agencyId(AGENCY_ID)
        .tenantId(42L)
        .status(HandshakeStatus.PENDING)
        .createDate(nowInUtc())
        .expiryDate(nowInUtc().plusSeconds(300))
        .build();
  }

  private AuthenticatedUser supportAdmin() {
    var user = new AuthenticatedUser();
    user.setUserId(SUPPORT_ADMIN_ID);
    user.setUsername("support.admin");
    user.setRoles(Set.of("global-support-admin"));
    return user;
  }

  private AuthenticatedUser consultant() {
    var user = new AuthenticatedUser();
    user.setUserId(CONSULTANT_ID);
    user.setUsername("consultant.user");
    user.setRoles(Set.of("consultant"));
    return user;
  }

  private InitiateHandshakeRequest supportRequest() {
    var request = new InitiateHandshakeRequest();
    request.setPurpose("SUPPORT_ACCESS");
    request.setConsultantId(CONSULTANT_ID);
    request.setAgencyId(AGENCY_ID);
    request.setPassword("secret");
    request.setOtp("123456");
    return request;
  }
}
