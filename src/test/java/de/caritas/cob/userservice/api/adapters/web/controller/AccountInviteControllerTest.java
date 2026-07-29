package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountAccessGateStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.InviteSendResult;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailDeliveryStatus;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateService;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
  void createInvite_allocationModes_areParsedAndPassedToTheCommand() {
    // TEN-INV-U3: the composer's AUTO/MANUAL allocation modes reach the orchestration untouched.
    var request = new AccountInviteController.CreateAccountInviteRequestDTO();
    request.targetRole = AccountInviteTargetRole.TENANT_ADMIN.name();
    request.recipientEmail = "owner@example.org";
    request.tenantIdAllocationMode = "AUTO";
    request.agencyIdAllocationMode = "MANUAL";
    request.agencyId = 9L;

    var invite = sampleInvite();
    when(accountInviteService.createInvite(any())).thenReturn(invite);
    when(accountInviteService.calculateAccessGate(invite))
        .thenReturn(AccountAccessGateStatus.BLOCKED_INVITE);

    controller.createInvite(request);

    var commandCaptor =
        ArgumentCaptor.forClass(AccountInviteService.CreateAccountInviteCommand.class);
    verify(accountInviteService).createInvite(commandCaptor.capture());
    assertEquals(IdAllocationMode.AUTO, commandCaptor.getValue().tenantIdAllocationMode());
    assertEquals(IdAllocationMode.MANUAL, commandCaptor.getValue().agencyIdAllocationMode());
  }

  @Test
  void createInvite_unknownAllocationMode_throwsBadRequest() {
    var request = new AccountInviteController.CreateAccountInviteRequestDTO();
    request.targetRole = AccountInviteTargetRole.TENANT_ADMIN.name();
    request.recipientEmail = "owner@example.org";
    request.tenantIdAllocationMode = "SOMETIMES";

    assertThrows(BadRequestException.class, () -> controller.createInvite(request));
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
    // No pending mandatory 2FA on this invite: the onboarding phase is terminal.
    assertNotNull(response.getBody());
    assertEquals("COMPLETED", response.getBody().phase);
  }

  @Test
  void acceptInvite_pendingTwoFactor_reportsResumablePhase() {
    // Resume contract (ORISO-Admin#569): while the mandatory 2FA activation is open, the accept
    // response tells the client to continue at the 2FA step instead of a terminal state.
    var invite = sampleInvite();
    invite.setStatus(AccountInviteStatus.ACCEPTED);
    invite.setTwoFactorStatus(TwoFactorGateStatus.PENDING_SETUP);
    when(accountInviteService.acceptInvite("token-3", null)).thenReturn(invite);
    when(accountInviteService.calculateAccessGate(invite))
        .thenReturn(AccountAccessGateStatus.BLOCKED_TWO_FACTOR);

    var response = controller.acceptInvite("token-3", null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("PENDING_2FA_ACTIVATION", response.getBody().phase);
    assertEquals(
        AccountAccessGateStatus.BLOCKED_TWO_FACTOR.name(), response.getBody().accessGateStatus);
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
  void createInvite_Should_throwBadRequest_When_targetRoleMissing() {
    var request = new AccountInviteController.CreateAccountInviteRequestDTO();
    request.targetRole = null;

    assertThrows(BadRequestException.class, () -> controller.createInvite(request));
  }

  @Test
  void createInvite_Should_throwBadRequest_When_targetRoleUnknown() {
    var request = new AccountInviteController.CreateAccountInviteRequestDTO();
    request.targetRole = "NOT_A_ROLE";

    assertThrows(BadRequestException.class, () -> controller.createInvite(request));
  }

  @Test
  void createInvite_Should_treatNullBodyAsEmptyAndFailValidation() {
    assertThrows(BadRequestException.class, () -> controller.createInvite(null));
  }

  @Test
  void listInvites_Should_delegateWithDefaults_When_paramsNull() {
    Page<AccountInvite> page = new PageImpl<>(List.of(sampleInvite()), PageRequest.of(0, 20), 1);
    when(accountInviteService.listInvites(null, null, null, 0, 20)).thenReturn(page);
    when(accountInviteService.calculateAccessGate(any())).thenReturn(AccountAccessGateStatus.READY);
    when(deliveryRepository.findFirstByAccountInviteIdOrderByCreateDateDesc(10L))
        .thenReturn(Optional.empty());

    var response = controller.listInvites(null, null, null, null, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(1, response.getBody().totalElements);
    assertEquals(1, response.getBody().content.size());
  }

  @Test
  void listInvites_Should_parseEnumsAndPagination() {
    Page<AccountInvite> page = new PageImpl<>(List.of(), PageRequest.of(1, 5), 0);
    when(accountInviteService.listInvites(
            AccountInviteTargetRole.COUNSELLOR, AccountInviteStatus.DRAFT, 7L, 1, 5))
        .thenReturn(page);

    var response =
        controller.listInvites(
            AccountInviteTargetRole.COUNSELLOR.name(), AccountInviteStatus.DRAFT.name(), 7L, 1, 5);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(0, response.getBody().content.size());
  }

  @Test
  void listInvites_Should_throwBadRequest_When_unknownEnum() {
    assertThrows(
        BadRequestException.class, () -> controller.listInvites("BOGUS", null, null, null, null));
  }

  @Test
  void sendInvite_Should_delegateAndReturnOk() {
    var request = new AccountInviteController.SendInviteRequestDTO();
    request.templateId = 3L;
    request.acceptBaseUrl = "https://x";
    var invite = sampleInvite();
    var delivery = InviteEmailDelivery.builder().status(InviteEmailDeliveryStatus.SENT).build();
    when(accountInviteService.sendInvite(any()))
        .thenReturn(new InviteSendResult(invite, delivery, "token", "url"));

    var response = controller.sendInvite(99L, request);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    var captor = ArgumentCaptor.forClass(AccountInviteService.SendInviteCommand.class);
    verify(accountInviteService).sendInvite(captor.capture());
    assertEquals(99L, captor.getValue().inviteId());
    assertEquals(3L, captor.getValue().templateId());
  }

  @Test
  void sendInvite_Should_useEmptyRequest_When_bodyNull() {
    var invite = sampleInvite();
    var delivery = InviteEmailDelivery.builder().status(InviteEmailDeliveryStatus.FAILED).build();
    when(accountInviteService.sendInvite(any()))
        .thenReturn(new InviteSendResult(invite, delivery, null, null));

    var response = controller.sendInvite(1L, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
  }

  @Test
  void resendInvite_Should_delegate() {
    var invite = sampleInvite();
    var delivery = InviteEmailDelivery.builder().status(InviteEmailDeliveryStatus.SENT).build();
    when(accountInviteService.resendInvite(any()))
        .thenReturn(new InviteSendResult(invite, delivery, "t", "u"));

    var response = controller.resendInvite(50L, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(accountInviteService).resendInvite(any());
  }

  @Test
  void revokeInvite_Should_delegate() {
    var invite = sampleInvite();
    when(accountInviteService.revokeInvite(10L)).thenReturn(invite);
    when(accountInviteService.calculateAccessGate(invite))
        .thenReturn(AccountAccessGateStatus.BLOCKED_INVITE);
    when(deliveryRepository.findFirstByAccountInviteIdOrderByCreateDateDesc(10L))
        .thenReturn(Optional.empty());

    var response = controller.revokeInvite(10L);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(accountInviteService).revokeInvite(10L);
  }

  @Test
  void waiveTwoFactor_Should_delegate() {
    var request = new AccountInviteController.WaiveTwoFactorRequestDTO();
    request.reason = "hardship";
    var invite = sampleInvite();
    when(accountInviteService.waiveTwoFactor(eq(10L), any())).thenReturn(invite);
    when(accountInviteService.calculateAccessGate(invite))
        .thenReturn(AccountAccessGateStatus.READY);

    var response = controller.waiveTwoFactor(10L, request);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(accountInviteService).waiveTwoFactor(eq(10L), any());
  }

  @Test
  void waiveTwoFactor_Should_acceptNullBody() {
    var invite = sampleInvite();
    when(accountInviteService.waiveTwoFactor(eq(10L), any())).thenReturn(invite);
    when(accountInviteService.calculateAccessGate(invite))
        .thenReturn(AccountAccessGateStatus.READY);

    var response = controller.waiveTwoFactor(10L, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
  }

  @Test
  void createTemplate_Should_returnCreated() {
    var request = new AccountInviteController.TemplateRequestDTO();
    request.kind = InviteEmailTemplateKind.TENANT_INVITE.name();
    request.name = "t";
    var template =
        InviteEmailTemplate.builder().id(5L).kind(InviteEmailTemplateKind.TENANT_INVITE).build();
    when(templateService.createTemplate(any())).thenReturn(template);

    var response = controller.createTemplate(request);

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertEquals(5L, response.getBody().id);
  }

  @Test
  void createTemplate_Should_throwBadRequest_When_kindMissing() {
    var request = new AccountInviteController.TemplateRequestDTO();
    request.kind = null;

    assertThrows(BadRequestException.class, () -> controller.createTemplate(request));
  }

  @Test
  void updateTemplate_Should_delegate() {
    var request = new AccountInviteController.TemplateRequestDTO();
    request.kind = InviteEmailTemplateKind.COUNSELLOR_INVITE.name();
    var template =
        InviteEmailTemplate.builder()
            .id(9L)
            .kind(InviteEmailTemplateKind.COUNSELLOR_INVITE)
            .build();
    when(templateService.updateTemplate(eq(9L), any())).thenReturn(template);

    var response = controller.updateTemplate(9L, request);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(9L, response.getBody().id);
  }

  @Test
  void listTemplates_Should_returnAll_When_kindNull() {
    var template = InviteEmailTemplate.builder().id(1L).build();
    when(templateService.listTemplates(null)).thenReturn(List.of(template));

    var response = controller.listTemplates(null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(1, response.getBody().size());
  }

  @Test
  void listTemplates_Should_filterByKind_When_provided() {
    when(templateService.listTemplates(InviteEmailTemplateKind.DPA_FORWARD)).thenReturn(List.of());

    var response = controller.listTemplates("DPA_FORWARD");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(0, response.getBody().size());
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
