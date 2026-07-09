package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.SendInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.WaiveTwoFactorCommand;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountInviteServiceTest {

  @Mock private AccountInviteRepository accountInviteRepository;
  @Mock private InviteEmailTemplateRepository templateRepository;
  @Mock private InviteEmailDeliveryRepository deliveryRepository;
  @Mock private AuthenticatedUser authenticatedUser;

  @InjectMocks private AccountInviteService service;

  @Test
  void sendInvite_Should_SetEmailSentAndPersistSnapshot_When_TemplateExists() {
    AccountInvite invite =
        AccountInvite.builder()
            .id(10L)
            .tenantId(7L)
            .recipientEmail("owner@example.org")
            .firstName("Ada")
            .targetRole(AccountInviteTargetRole.TENANT_ADMIN)
            .status(AccountInviteStatus.DRAFT)
            .build();
    InviteEmailTemplate template =
        InviteEmailTemplate.builder()
            .id(20L)
            .kind(InviteEmailTemplateKind.TENANT_INVITE)
            .subject("Welcome {{firstName}}")
            .body("Use {{inviteLink}}")
            .active(true)
            .build();
    when(accountInviteRepository.findById(10L)).thenReturn(Optional.of(invite));
    when(templateRepository.findById(20L)).thenReturn(Optional.of(template));
    when(accountInviteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(deliveryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result =
        service.sendInvite(new SendInviteCommand(10L, 20L, "https://app.oriso.org/account-invite"));

    assertThat(result.invite().getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(result.rawToken()).isNotBlank();
    assertThat(result.invite().getTokenHash()).isNotEqualTo(result.rawToken());
    ArgumentCaptor<InviteEmailDelivery> deliveryCaptor =
        ArgumentCaptor.forClass(InviteEmailDelivery.class);
    verify(deliveryRepository).save(deliveryCaptor.capture());
    assertThat(deliveryCaptor.getValue().getSubjectSnapshot()).isEqualTo("Welcome Ada");
    assertThat(deliveryCaptor.getValue().getBodySnapshot()).contains(result.rawToken());
    assertThat(deliveryCaptor.getValue().getRecipientSnapshot()).isEqualTo("owner@example.org");
    assertThat(deliveryCaptor.getValue().getStatus()).isEqualTo(InviteEmailDeliveryStatus.SENT);
  }

  @Test
  void resendInvite_Should_SupersedeOldInviteAndCreateNewEmailSentInvite() {
    AccountInvite oldInvite =
        AccountInvite.builder()
            .id(10L)
            .tenantId(7L)
            .recipientEmail("counsellor@example.org")
            .firstName("Grace")
            .targetRole(AccountInviteTargetRole.COUNSELLOR)
            .status(AccountInviteStatus.EMAIL_SENT)
            .build();
    InviteEmailTemplate template =
        InviteEmailTemplate.builder()
            .id(20L)
            .kind(InviteEmailTemplateKind.COUNSELLOR_INVITE)
            .subject("Again")
            .body("Use {{inviteLink}}")
            .active(true)
            .build();
    when(accountInviteRepository.findById(10L)).thenReturn(Optional.of(oldInvite));
    when(templateRepository.findById(20L)).thenReturn(Optional.of(template));
    when(accountInviteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(deliveryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result =
        service.resendInvite(
            new SendInviteCommand(10L, 20L, "https://app.oriso.org/account-invite"));

    assertThat(oldInvite.getStatus()).isEqualTo(AccountInviteStatus.SUPERSEDED);
    assertThat(result.invite()).isNotSameAs(oldInvite);
    assertThat(result.invite().getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(result.invite().getSupersededByInviteId()).isNull();
    assertThat(result.invite().getRecipientEmail()).isEqualTo(oldInvite.getRecipientEmail());
  }

  @Test
  void deliverySnapshot_Should_RemainUnchanged_When_TemplateIsChangedLater() {
    AccountInvite invite =
        AccountInvite.builder()
            .id(10L)
            .recipientEmail("owner@example.org")
            .targetRole(AccountInviteTargetRole.TENANT_ADMIN)
            .status(AccountInviteStatus.DRAFT)
            .build();
    InviteEmailTemplate template =
        InviteEmailTemplate.builder()
            .id(20L)
            .kind(InviteEmailTemplateKind.TENANT_INVITE)
            .subject("Original {{email}}")
            .body("Original body")
            .active(true)
            .build();
    when(accountInviteRepository.findById(10L)).thenReturn(Optional.of(invite));
    when(templateRepository.findById(20L)).thenReturn(Optional.of(template));
    when(accountInviteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(deliveryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.sendInvite(new SendInviteCommand(10L, 20L, "https://app.oriso.org/account-invite"));
    template.setSubject("Changed");
    template.setBody("Changed");

    ArgumentCaptor<InviteEmailDelivery> deliveryCaptor =
        ArgumentCaptor.forClass(InviteEmailDelivery.class);
    verify(deliveryRepository).save(deliveryCaptor.capture());
    assertThat(deliveryCaptor.getValue().getSubjectSnapshot())
        .isEqualTo("Original owner@example.org");
    assertThat(deliveryCaptor.getValue().getBodySnapshot()).isEqualTo("Original body");
  }

  @Test
  void calculateAccessGate_Should_BlockRequiredTwoFactorUntilWaived() {
    AccountInvite invite =
        AccountInvite.builder()
            .status(AccountInviteStatus.ACCEPTED)
            .emailVerificationStatus(EmailVerificationStatus.VERIFIED)
            .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
            .build();

    assertThat(service.calculateAccessGate(invite))
        .isEqualTo(AccountAccessGateStatus.BLOCKED_TWO_FACTOR);

    when(authenticatedUser.getUserId()).thenReturn("admin-1");
    service.waiveTwoFactor(invite, new WaiveTwoFactorCommand("Temporary migration waiver"));

    assertThat(invite.getTwoFactorStatus()).isEqualTo(TwoFactorGateStatus.WAIVED);
    assertThat(invite.getTwoFactorWaivedBy()).isEqualTo("admin-1");
    assertThat(invite.getTwoFactorWaiverReason()).isEqualTo("Temporary migration waiver");
    assertThat(service.calculateAccessGate(invite)).isEqualTo(AccountAccessGateStatus.READY);
  }

  @Test
  void createInvite_Should_StartAsDraftAndKeepProvisioningSeparate() {
    when(authenticatedUser.getUserId()).thenReturn("admin-1");
    when(authenticatedUser.getUsername()).thenReturn("admin@example.org");
    when(accountInviteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    AccountInvite invite =
        service.createInvite(
            new CreateAccountInviteCommand(
                AccountInviteTargetRole.COUNSELLOR,
                7L,
                "new@example.org",
                "New",
                "Counsellor",
                null,
                null,
                30L));

    assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
    assertThat(invite.getEmailVerificationStatus()).isEqualTo(EmailVerificationStatus.PENDING);
    assertThat(invite.getTwoFactorStatus()).isEqualTo(TwoFactorGateStatus.PENDING_SETUP);
    assertThat(invite.getTokenHash()).isNull();
  }
}
