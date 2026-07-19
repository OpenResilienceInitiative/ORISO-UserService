package de.caritas.cob.userservice.api.service.handshake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.port.out.HandshakeAuditEventRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeSessionRepository;
import de.caritas.cob.userservice.api.service.handshake.HandshakeService.InitiateHandshakeRequest;
import java.time.LocalDateTime;
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

  @Mock private HandshakeSessionRepository handshakeSessionRepository;
  @Mock private HandshakeAuditEventRepository handshakeAuditEventRepository;
  @Mock private KeycloakAuthClient keycloakAuthClient;
  @Mock private HandshakeCompletionHandler completionHandler;

  private HandshakeService handshakeService;

  @BeforeEach
  void setUp() {
    handshakeService =
        new HandshakeService(
            handshakeSessionRepository,
            handshakeAuditEventRepository,
            keycloakAuthClient,
            List.of(completionHandler));
    ReflectionTestUtils.setField(handshakeService, "ttlSeconds", 300L);
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
    request.setCounterpartId(CONSULTANT_ID);
    request.setPassword("secret");
    request.setOtp("123456");
    return request;
  }

  // --- initiate ---

  @Test
  void initiate_Should_CreatePendingSessionWithTtl_When_FreshCredentialsAndRoleAreValid() {
    when(keycloakAuthClient.verifyWithOtp("support.admin", "secret", "123456")).thenReturn(true);
    when(handshakeSessionRepository.save(any(HandshakeSession.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var item = handshakeService.initiate(supportAdmin(), supportRequest());

    var captor = ArgumentCaptor.forClass(HandshakeSession.class);
    verify(handshakeSessionRepository).save(captor.capture());
    var saved = captor.getValue();
    assertThat(saved.getStatus()).isEqualTo(HandshakeSession.HandshakeStatus.PENDING);
    assertThat(saved.getInitiatorId()).isEqualTo(SUPPORT_ADMIN_ID);
    assertThat(saved.getCounterpartId()).isEqualTo(CONSULTANT_ID);
    assertThat(saved.getExpiryDate())
        .isAfter(LocalDateTime.now().plusSeconds(200))
        .isBefore(LocalDateTime.now().plusSeconds(400));
    assertThat(item.getId()).isNotBlank();
    assertThat(item.getStatus()).isEqualTo("PENDING");
    verify(handshakeAuditEventRepository).save(any());
  }

  @Test
  void initiate_Should_Forbid_When_FreshCredentialCheckFails() {
    when(keycloakAuthClient.verifyWithOtp(anyString(), anyString(), anyString())).thenReturn(false);

    assertThatThrownBy(() -> handshakeService.initiate(supportAdmin(), supportRequest()))
        .isInstanceOf(ForbiddenException.class);

    verify(handshakeSessionRepository, never()).save(any());
  }

  @Test
  void initiate_Should_Forbid_When_InitiatorLacksPurposeRole() {
    assertThatThrownBy(() -> handshakeService.initiate(consultant(), supportRequest()))
        .isInstanceOf(ForbiddenException.class);

    verify(keycloakAuthClient, never()).verifyWithOtp(anyString(), anyString(), anyString());
    verify(handshakeSessionRepository, never()).save(any());
  }

  @Test
  void initiate_Should_Reject_When_InitiatorTargetsThemselves() {
    var request = supportRequest();
    request.setCounterpartId(SUPPORT_ADMIN_ID);

    assertThatThrownBy(() -> handshakeService.initiate(supportAdmin(), request))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void initiate_Should_Reject_When_PurposeIsUnknown() {
    var request = supportRequest();
    request.setPurpose("TAKE_OVER_THE_WORLD");

    assertThatThrownBy(() -> handshakeService.initiate(supportAdmin(), request))
        .isInstanceOf(BadRequestException.class);
  }

  // --- confirm ---

  private HandshakeSession pendingSession() {
    return HandshakeSession.builder()
        .id("hs-1")
        .purpose(HandshakePurpose.SUPPORT_ACCESS)
        .initiatorId(SUPPORT_ADMIN_ID)
        .counterpartId(CONSULTANT_ID)
        .status(HandshakeSession.HandshakeStatus.PENDING)
        .createDate(LocalDateTime.now().minusMinutes(1))
        .expiryDate(LocalDateTime.now().plusMinutes(4))
        .build();
  }

  @Test
  void confirm_Should_ConfirmAndNotifyHandlers_When_CounterpartPasswordIsValid() {
    var session = pendingSession();
    when(handshakeSessionRepository.findById("hs-1")).thenReturn(Optional.of(session));
    when(keycloakAuthClient.verifyIgnoringOtp("consultant.user", "pw")).thenReturn(true);
    when(handshakeSessionRepository.save(any(HandshakeSession.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(completionHandler.supports(HandshakePurpose.SUPPORT_ACCESS)).thenReturn(true);

    var item = handshakeService.confirm(consultant(), "hs-1", "pw");

    assertThat(item.getStatus()).isEqualTo("CONFIRMED");
    assertThat(session.getStatus()).isEqualTo(HandshakeSession.HandshakeStatus.CONFIRMED);
    assertThat(session.getConfirmedDate()).isNotNull();
    verify(completionHandler).onConfirmed(session);
  }

  @Test
  void confirm_Should_Forbid_When_CallerIsNotTheCounterpart() {
    when(handshakeSessionRepository.findById("hs-1")).thenReturn(Optional.of(pendingSession()));

    assertThatThrownBy(() -> handshakeService.confirm(supportAdmin(), "hs-1", "pw"))
        .isInstanceOf(ForbiddenException.class);

    verify(completionHandler, never()).onConfirmed(any());
  }

  @Test
  void confirm_Should_Forbid_When_PasswordCheckFails() {
    when(handshakeSessionRepository.findById("hs-1")).thenReturn(Optional.of(pendingSession()));
    when(keycloakAuthClient.verifyIgnoringOtp("consultant.user", "pw")).thenReturn(false);

    assertThatThrownBy(() -> handshakeService.confirm(consultant(), "hs-1", "pw"))
        .isInstanceOf(ForbiddenException.class);

    verify(completionHandler, never()).onConfirmed(any());
  }

  @Test
  void confirm_Should_ExpireSessionWithAudit_When_WindowLapsed() {
    var session = pendingSession();
    session.setExpiryDate(LocalDateTime.now().minusSeconds(10));
    when(handshakeSessionRepository.findById("hs-1")).thenReturn(Optional.of(session));
    when(handshakeSessionRepository.save(any(HandshakeSession.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    assertThatThrownBy(() -> handshakeService.confirm(consultant(), "hs-1", "pw"))
        .isInstanceOf(BadRequestException.class);

    assertThat(session.getStatus()).isEqualTo(HandshakeSession.HandshakeStatus.EXPIRED);
    verify(completionHandler, never()).onConfirmed(any());
    // one audit entry for the lapse ("session was not established")
    verify(handshakeAuditEventRepository).save(any());
  }

  @Test
  void confirm_Should_Reject_When_SessionAlreadyConfirmed() {
    var session = pendingSession();
    session.setStatus(HandshakeSession.HandshakeStatus.CONFIRMED);
    when(handshakeSessionRepository.findById("hs-1")).thenReturn(Optional.of(session));

    assertThatThrownBy(() -> handshakeService.confirm(consultant(), "hs-1", "pw"))
        .isInstanceOf(BadRequestException.class);

    verify(completionHandler, never()).onConfirmed(any());
  }

  // --- pending lookup + sweeps ---

  @Test
  void pendingForCounterpart_Should_ReturnOnlyUnexpiredPendingSessions() {
    var session = pendingSession();
    when(handshakeSessionRepository.findAllByCounterpartIdAndStatusAndExpiryDateAfter(
            any(), any(), any(LocalDateTime.class)))
        .thenReturn(List.of(session));

    var items = handshakeService.pendingForCounterpart(consultant());

    assertThat(items).hasSize(1);
    assertThat(items.get(0).getPurpose()).isEqualTo("SUPPORT_ACCESS");
  }

  @Test
  void sweepExpired_Should_ExpireLapsedPendingSessions_WithAuditEntry() {
    var session = pendingSession();
    session.setExpiryDate(LocalDateTime.now().minusMinutes(1));
    when(handshakeSessionRepository.findAllByStatusAndExpiryDateBefore(
            any(), any(LocalDateTime.class)))
        .thenReturn(List.of(session));
    when(handshakeSessionRepository.save(any(HandshakeSession.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    handshakeService.sweepExpired();

    assertThat(session.getStatus()).isEqualTo(HandshakeSession.HandshakeStatus.EXPIRED);
    verify(handshakeAuditEventRepository).save(any());
  }

  @Test
  void purgeOldAuditEvents_Should_DeleteEntriesOlderThanRetention() {
    ReflectionTestUtils.setField(handshakeService, "auditRetentionMonths", 12L);

    handshakeService.purgeOldAuditEvents();

    var captor = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(handshakeAuditEventRepository).deleteAllByCreateDateBefore(captor.capture());
    assertThat(captor.getValue())
        .isBefore(LocalDateTime.now().minusMonths(11))
        .isAfter(LocalDateTime.now().minusMonths(13));
  }
}
