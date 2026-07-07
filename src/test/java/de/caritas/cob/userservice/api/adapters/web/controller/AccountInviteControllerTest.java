package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountAccessGateStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.InviteSendResult;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailDeliveryStatus;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateService;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;

@ExtendWith(MockitoExtension.class)
class AccountInviteControllerTest {

  @Mock private AccountInviteService accountInviteService;
  @Mock private InviteEmailTemplateService templateService;
  @Mock private InviteEmailDeliveryRepository deliveryRepository;

  private AccountInviteController controller;

  @BeforeEach
  void setUp() {
    controller =
        new AccountInviteController(accountInviteService, templateService, deliveryRepository);
  }

  @Test
  void createInvite_validRequest_delegatesAndReturnsCreated() {
    // Business reason: admin invite creation must persist intended recipient and role details.
    var request = new AccountInviteController.CreateAccountInviteRequestDTO();
    request.targetRole = AccountInviteTargetRole.COUNSELLOR.name();
    request.tenantId = 7L;
    request.recipientEmail = "invitee@example.org";

    var invite = sampleInvite();
    when(accountInviteService.createInvite(any())).thenReturn(invite);
    when(accountInviteService.calculateAccessGate(invite))
        .thenReturn(AccountAccessGateStatus.BLOCKED_INVITE);

    var response = controller.createInvite(request);

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertNotNull(response.getBody());
    var commandCaptor =
        ArgumentCaptor.forClass(AccountInviteService.CreateAccountInviteCommand.class);
    verify(accountInviteService).createInvite(commandCaptor.capture());
    assertEquals(AccountInviteTargetRole.COUNSELLOR, commandCaptor.getValue().targetRole());
    assertEquals("invitee@example.org", commandCaptor.getValue().recipientEmail());
  }

  @Test
  void createInvite_withTemplateId_sendsInviteAndReturnsCreated() {
    // Business reason: template-triggered invitation must send immediately and return accept URL
    // metadata.
    var request = new AccountInviteController.CreateAccountInviteRequestDTO();
    request.targetRole = AccountInviteTargetRole.COUNSELLOR.name();
    request.recipientEmail = "invitee@example.org";
    request.templateId = 12L;
    request.acceptBaseUrl = "https://example.org/invite";

    var invite = sampleInvite();
    var delivery = InviteEmailDelivery.builder().status(InviteEmailDeliveryStatus.SENT).build();
    var sendResult = new InviteSendResult(invite, delivery, "raw-token", "accept-url");
    when(accountInviteService.createInvite(any())).thenReturn(invite);
    when(accountInviteService.sendInvite(any())).thenReturn(sendResult);

    var response = controller.createInvite(request);

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertEquals("raw-token", response.getBody().rawToken);
    var sendCaptor = ArgumentCaptor.forClass(AccountInviteService.SendInviteCommand.class);
    verify(accountInviteService).sendInvite(sendCaptor.capture());
    assertEquals(12L, sendCaptor.getValue().templateId());
  }

  @Test
  void createInvite_duplicateInvite_throwsConflictException() {
    // Business reason: duplicate invite attempts should stop to prevent inconsistent provisioning
    // states.
    var request = new AccountInviteController.CreateAccountInviteRequestDTO();
    request.targetRole = AccountInviteTargetRole.COUNSELLOR.name();
    request.recipientEmail = "dup@example.org";
    when(accountInviteService.createInvite(any())).thenThrow(new ConflictException("duplicate"));

    assertThrows(ConflictException.class, () -> controller.createInvite(request));
  }

  @Test
  void createInvite_invalidAgency_throwsNotFoundException() {
    // Business reason: invite creation must fail fast when agency references do not exist.
    var request = new AccountInviteController.CreateAccountInviteRequestDTO();
    request.targetRole = AccountInviteTargetRole.COUNSELLOR.name();
    request.recipientEmail = "dup@example.org";
    when(accountInviteService.createInvite(any()))
        .thenThrow(new NotFoundException("agency not found"));

    assertThrows(NotFoundException.class, () -> controller.createInvite(request));
  }

  @Test
  void acceptInvite_validToken_delegatesAndReturnsOk() {
    // Business reason: valid invitation acceptance should transition invite state and return
    // confirmation data.
    var request = new AccountInviteController.AcceptInviteRequestDTO();
    request.acceptedByUserId = "user-1";
    var invite = sampleInvite();
    when(accountInviteService.acceptInvite("token-1", "user-1")).thenReturn(invite);
    when(accountInviteService.calculateAccessGate(invite))
        .thenReturn(AccountAccessGateStatus.READY);
    when(deliveryRepository.findFirstByAccountInviteIdOrderByCreateDateDesc(10L))
        .thenReturn(
            Optional.of(
                InviteEmailDelivery.builder().status(InviteEmailDeliveryStatus.SENT).build()));

    var response = controller.acceptInvite("token-1", request);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(accountInviteService).acceptInvite("token-1", "user-1");
  }

  @Test
  void acceptInvite_nullBody_passesNullAcceptedByUserId() {
    // Business reason: anonymous acceptance flows must still work without explicit requester
    // payload.
    var invite = sampleInvite();
    when(accountInviteService.acceptInvite("token-2", null)).thenReturn(invite);
    when(accountInviteService.calculateAccessGate(invite))
        .thenReturn(AccountAccessGateStatus.READY);

    var response = controller.acceptInvite("token-2", null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(accountInviteService).acceptInvite("token-2", null);
  }

  @Test
  void listInvites_adminEndpoints_havePreAuthorizeAnnotation() throws Exception {
    // Business reason: admin-only invite operations must enforce role-based policy at controller
    // boundary.
    assertHasPreAuthorize(
        "createInvite", AccountInviteController.CreateAccountInviteRequestDTO.class);
    assertHasPreAuthorize(
        "listInvites", String.class, String.class, Long.class, Integer.class, Integer.class);
    assertHasPreAuthorize(
        "sendInvite", Long.class, AccountInviteController.SendInviteRequestDTO.class);
    assertHasPreAuthorize(
        "resendInvite", Long.class, AccountInviteController.SendInviteRequestDTO.class);
    assertHasPreAuthorize("revokeInvite", Long.class);
    assertHasPreAuthorize("createTemplate", AccountInviteController.TemplateRequestDTO.class);
    assertHasPreAuthorize(
        "updateTemplate", Long.class, AccountInviteController.TemplateRequestDTO.class);
    assertHasPreAuthorize("listTemplates", String.class);
  }

  private void assertHasPreAuthorize(String methodName, Class<?>... paramTypes) throws Exception {
    Method method = AccountInviteController.class.getMethod(methodName, paramTypes);
    var annotation = method.getAnnotation(PreAuthorize.class);
    assertNotNull(annotation);
  }

  private static AccountInvite sampleInvite() {
    return AccountInvite.builder()
        .id(10L)
        .targetRole(AccountInviteTargetRole.COUNSELLOR)
        .status(AccountInviteStatus.DRAFT)
        .recipientEmail("invitee@example.org")
        .createDate(LocalDateTime.now())
        .build();
  }
}
