package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
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
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdReservation;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

@ExtendWith(MockitoExtension.class)
class AccountInviteServiceTest {

  @Mock private AccountInviteRepository accountInviteRepository;
  @Mock private InviteEmailTemplateRepository templateRepository;
  @Mock private InviteEmailDeliveryRepository deliveryRepository;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private TenantService tenantService;
  @Mock private TenantIdAllocationClient tenantIdAllocationClient;
  @Mock private AgencyIdAllocationClient agencyIdAllocationClient;

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

  // ---------------------------------------------------------------------------
  // Extended coverage — 2026-07-10
  // ---------------------------------------------------------------------------

  // --- createInvite guards ---

  @Test
  void createInvite_Should_throwBadRequest_When_commandNull() {
    assertThatThrownBy(() -> service.createInvite(null)).isInstanceOf(BadRequestException.class);
  }

  @Test
  void createInvite_Should_throwBadRequest_When_targetRoleNull() {
    var command =
        new CreateAccountInviteCommand(null, 7L, "a@example.org", "A", "B", null, null, null);
    assertThatThrownBy(() -> service.createInvite(command)).isInstanceOf(BadRequestException.class);
  }

  @Test
  void createInvite_Should_throwBadRequest_When_recipientEmailBlank() {
    var command =
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.COUNSELLOR, 7L, "   ", "A", "B", null, null, null);
    assertThatThrownBy(() -> service.createInvite(command)).isInstanceOf(BadRequestException.class);
  }

  @Test
  void createInvite_Should_defaultTwoFactorNotRequired_When_targetRoleNotCounsellor() {
    when(authenticatedUser.getUserId()).thenReturn("admin-1");
    when(authenticatedUser.getUsername()).thenReturn("admin@example.org");
    when(accountInviteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    AccountInvite invite =
        service.createInvite(
            new CreateAccountInviteCommand(
                AccountInviteTargetRole.TENANT_ADMIN,
                7L,
                "new@example.org",
                null,
                null,
                null,
                null,
                null));

    assertThat(invite.getTwoFactorStatus()).isEqualTo(TwoFactorGateStatus.NOT_REQUIRED);
    assertThat(invite.getFirstName()).isNull();
  }

  @Test
  void createInvite_Should_throwBadRequest_When_expiresInDaysOutOfRange() {
    var command =
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.COUNSELLOR, 7L, "new@example.org", "A", "B", null, null, 400L);

    assertThatThrownBy(() -> service.createInvite(command)).isInstanceOf(BadRequestException.class);
  }

  // --- listInvites ---

  @Test
  void listInvites_Should_delegateToRepositoryWithClampedPageAndSize() {
    Page<AccountInvite> page = new PageImpl<>(java.util.List.of());
    when(accountInviteRepository.findAllByFilters(any(), any(), any(), any())).thenReturn(page);

    Page<AccountInvite> result =
        service.listInvites(
            AccountInviteTargetRole.COUNSELLOR, AccountInviteStatus.DRAFT, 7L, -1, -1);

    assertThat(result).isSameAs(page);
    verify(accountInviteRepository)
        .findAllByFilters(
            eq(7L),
            eq(AccountInviteTargetRole.COUNSELLOR),
            eq(AccountInviteStatus.DRAFT),
            argThat(pr -> pr.getPageNumber() == 0 && pr.getPageSize() == 20));
  }

  @Test
  void listInvites_Should_clampSize_When_tooLarge() {
    Page<AccountInvite> page = new PageImpl<>(java.util.List.of());
    when(accountInviteRepository.findAllByFilters(any(), any(), any(), any())).thenReturn(page);

    service.listInvites(null, null, null, 2, 500);

    verify(accountInviteRepository)
        .findAllByFilters(
            eq(null),
            eq(null),
            eq(null),
            argThat(pr -> pr.getPageNumber() == 2 && pr.getPageSize() == 100));
  }

  // --- revokeInvite ---

  @Test
  void revokeInvite_Should_setRevokedFields_When_notAccepted() {
    AccountInvite invite =
        AccountInvite.builder().id(1L).status(AccountInviteStatus.EMAIL_SENT).build();
    when(accountInviteRepository.findById(1L)).thenReturn(Optional.of(invite));
    when(authenticatedUser.getUserId()).thenReturn("admin-1");
    when(accountInviteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    AccountInvite result = service.revokeInvite(1L);

    assertThat(result.getStatus()).isEqualTo(AccountInviteStatus.REVOKED);
    assertThat(result.getRevokedByUserId()).isEqualTo("admin-1");
    assertThat(result.getRevokedAt()).isNotNull();
  }

  @Test
  void revokeInvite_Should_throwBadRequest_When_alreadyAccepted() {
    AccountInvite invite =
        AccountInvite.builder().id(1L).status(AccountInviteStatus.ACCEPTED).build();
    when(accountInviteRepository.findById(1L)).thenReturn(Optional.of(invite));

    assertThatThrownBy(() -> service.revokeInvite(1L)).isInstanceOf(BadRequestException.class);
  }

  @Test
  void revokeInvite_Should_throwNotFound_When_inviteMissing() {
    when(accountInviteRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.revokeInvite(99L)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void revokeInvite_Should_throwBadRequest_When_inviteIdNull() {
    assertThatThrownBy(() -> service.revokeInvite(null)).isInstanceOf(BadRequestException.class);
  }

  // --- acceptInvite ---

  @Test
  void acceptInvite_Should_throwBadRequest_When_tokenBlank() {
    assertThatThrownBy(() -> service.acceptInvite("  ", "user-1"))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void acceptInvite_Should_throwNotFound_When_tokenNotFound() {
    when(accountInviteRepository.findByTokenHash(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.acceptInvite("raw-token", "user-1"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void acceptInvite_Should_expireAndThrow_When_pastExpiry() {
    AccountInvite invite =
        AccountInvite.builder()
            .id(1L)
            .status(AccountInviteStatus.EMAIL_SENT)
            .expiresAt(LocalDateTime.now().minusDays(1))
            .build();
    when(accountInviteRepository.findByTokenHash(any())).thenReturn(Optional.of(invite));
    when(accountInviteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    assertThatThrownBy(() -> service.acceptInvite("raw-token", "user-1"))
        .isInstanceOf(BadRequestException.class);
    assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.EXPIRED);
  }

  @Test
  void acceptInvite_Should_throwBadRequest_When_statusNotActive() {
    AccountInvite invite =
        AccountInvite.builder().id(1L).status(AccountInviteStatus.REVOKED).build();
    when(accountInviteRepository.findByTokenHash(any())).thenReturn(Optional.of(invite));

    assertThatThrownBy(() -> service.acceptInvite("raw-token", "user-1"))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void acceptInvite_Should_activateInvite_When_emailSentAndNotExpired() {
    AccountInvite invite =
        AccountInvite.builder()
            .id(1L)
            .status(AccountInviteStatus.EMAIL_SENT)
            .expiresAt(LocalDateTime.now().plusDays(1))
            .build();
    when(accountInviteRepository.findByTokenHash(any())).thenReturn(Optional.of(invite));
    when(accountInviteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    AccountInvite result = service.acceptInvite("raw-token", "user-1");

    assertThat(result.getStatus()).isEqualTo(AccountInviteStatus.ACCEPTED);
    assertThat(result.getAcceptedByUserId()).isEqualTo("user-1");
    assertThat(result.getEmailVerificationStatus()).isEqualTo(EmailVerificationStatus.VERIFIED);
  }

  @Test
  void acceptInvite_Should_throwBadRequest_When_statusDraftAndNoExpiry() {
    // DRAFT invites have never been delivered to the recipient, so accepting one would
    // bypass email verification. acceptInvite() must reject any status other than EMAIL_SENT.
    AccountInvite invite =
        AccountInvite.builder().id(1L).status(AccountInviteStatus.DRAFT).expiresAt(null).build();
    when(accountInviteRepository.findByTokenHash(any())).thenReturn(Optional.of(invite));

    assertThatThrownBy(() -> service.acceptInvite("raw-token", "user-1"))
        .isInstanceOf(BadRequestException.class);
  }

  // --- resendInvite guards ---

  @Test
  void resendInvite_Should_throwBadRequest_When_oldInviteAccepted() {
    AccountInvite invite =
        AccountInvite.builder().id(1L).status(AccountInviteStatus.ACCEPTED).build();
    when(accountInviteRepository.findById(1L)).thenReturn(Optional.of(invite));

    assertThatThrownBy(() -> service.resendInvite(new SendInviteCommand(1L, 20L, "https://x")))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void resendInvite_Should_throwBadRequest_When_oldInviteRevoked() {
    AccountInvite invite =
        AccountInvite.builder().id(1L).status(AccountInviteStatus.REVOKED).build();
    when(accountInviteRepository.findById(1L)).thenReturn(Optional.of(invite));

    assertThatThrownBy(() -> service.resendInvite(new SendInviteCommand(1L, 20L, "https://x")))
        .isInstanceOf(BadRequestException.class);
  }

  // --- sendInvite (private, via public entry point) guards ---

  @Test
  void sendInvite_Should_throwBadRequest_When_inviteAccepted() {
    AccountInvite invite =
        AccountInvite.builder().id(1L).status(AccountInviteStatus.ACCEPTED).build();
    when(accountInviteRepository.findById(1L)).thenReturn(Optional.of(invite));
    when(templateRepository.findById(20L))
        .thenReturn(Optional.of(InviteEmailTemplate.builder().id(20L).build()));

    assertThatThrownBy(() -> service.sendInvite(new SendInviteCommand(1L, 20L, "https://x")))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void sendInvite_Should_throwBadRequest_When_inviteRevoked() {
    AccountInvite invite =
        AccountInvite.builder().id(1L).status(AccountInviteStatus.REVOKED).build();
    when(accountInviteRepository.findById(1L)).thenReturn(Optional.of(invite));
    when(templateRepository.findById(20L))
        .thenReturn(Optional.of(InviteEmailTemplate.builder().id(20L).build()));

    assertThatThrownBy(() -> service.sendInvite(new SendInviteCommand(1L, 20L, "https://x")))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void sendInvite_Should_throwBadRequest_When_inviteSuperseded() {
    // SUPERSEDED invites belong to a completed resend cycle and must never be re-sent.
    AccountInvite invite =
        AccountInvite.builder().id(1L).status(AccountInviteStatus.SUPERSEDED).build();
    when(accountInviteRepository.findById(1L)).thenReturn(Optional.of(invite));
    when(templateRepository.findById(20L))
        .thenReturn(Optional.of(InviteEmailTemplate.builder().id(20L).build()));

    assertThatThrownBy(() -> service.sendInvite(new SendInviteCommand(1L, 20L, "https://x")))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void sendInvite_Should_keepExistingFutureExpiry_When_alreadySet() {
    LocalDateTime future = LocalDateTime.now().plusDays(10);
    AccountInvite invite =
        AccountInvite.builder()
            .id(1L)
            .recipientEmail("a@example.org")
            .status(AccountInviteStatus.DRAFT)
            .expiresAt(future)
            .build();
    InviteEmailTemplate template =
        InviteEmailTemplate.builder().id(20L).subject("s").body("b").build();
    when(accountInviteRepository.findById(1L)).thenReturn(Optional.of(invite));
    when(templateRepository.findById(20L)).thenReturn(Optional.of(template));
    when(accountInviteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(deliveryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.sendInvite(new SendInviteCommand(1L, 20L, "https://x"));

    assertThat(invite.getExpiresAt()).isEqualTo(future);
  }

  // --- findInvite / findTemplate guards ---

  @Test
  void sendInvite_Should_throwBadRequest_When_inviteIdNull() {
    assertThatThrownBy(() -> service.sendInvite(new SendInviteCommand(null, 20L, "https://x")))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void sendInvite_Should_throwNotFound_When_inviteMissing() {
    when(accountInviteRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.sendInvite(new SendInviteCommand(99L, 20L, "https://x")))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void sendInvite_Should_throwBadRequest_When_templateIdNull() {
    AccountInvite invite = AccountInvite.builder().id(1L).status(AccountInviteStatus.DRAFT).build();
    when(accountInviteRepository.findById(1L)).thenReturn(Optional.of(invite));

    assertThatThrownBy(() -> service.sendInvite(new SendInviteCommand(1L, null, "https://x")))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void sendInvite_Should_throwNotFound_When_templateMissing() {
    AccountInvite invite = AccountInvite.builder().id(1L).status(AccountInviteStatus.DRAFT).build();
    when(accountInviteRepository.findById(1L)).thenReturn(Optional.of(invite));
    when(templateRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.sendInvite(new SendInviteCommand(1L, 99L, "https://x")))
        .isInstanceOf(NotFoundException.class);
  }

  // --- calculateAccessGate additional branches ---

  @Test
  void calculateAccessGate_Should_returnBlockedInvite_When_inviteNull() {
    assertThat(service.calculateAccessGate(null)).isEqualTo(AccountAccessGateStatus.BLOCKED_INVITE);
  }

  @Test
  void calculateAccessGate_Should_returnBlockedInvite_When_statusNotAccepted() {
    AccountInvite invite = AccountInvite.builder().status(AccountInviteStatus.DRAFT).build();

    assertThat(service.calculateAccessGate(invite))
        .isEqualTo(AccountAccessGateStatus.BLOCKED_INVITE);
  }

  @Test
  void calculateAccessGate_Should_returnBlockedEmail_When_emailNotVerified() {
    AccountInvite invite =
        AccountInvite.builder()
            .status(AccountInviteStatus.ACCEPTED)
            .emailVerificationStatus(EmailVerificationStatus.PENDING)
            .build();

    assertThat(service.calculateAccessGate(invite))
        .isEqualTo(AccountAccessGateStatus.BLOCKED_EMAIL);
  }

  @Test
  void calculateAccessGate_Should_returnReady_When_emailNotRequired() {
    AccountInvite invite =
        AccountInvite.builder()
            .status(AccountInviteStatus.ACCEPTED)
            .emailVerificationStatus(EmailVerificationStatus.NOT_REQUIRED)
            .twoFactorStatus(TwoFactorGateStatus.NOT_REQUIRED)
            .build();

    assertThat(service.calculateAccessGate(invite)).isEqualTo(AccountAccessGateStatus.READY);
  }

  @Test
  void calculateAccessGate_Should_returnReady_When_twoFactorActive() {
    AccountInvite invite =
        AccountInvite.builder()
            .status(AccountInviteStatus.ACCEPTED)
            .emailVerificationStatus(EmailVerificationStatus.VERIFIED)
            .twoFactorStatus(TwoFactorGateStatus.ACTIVE)
            .build();

    assertThat(service.calculateAccessGate(invite)).isEqualTo(AccountAccessGateStatus.READY);
  }

  @Test
  void calculateAccessGate_Should_returnReady_When_twoFactorDisabledByPolicy() {
    AccountInvite invite =
        AccountInvite.builder()
            .status(AccountInviteStatus.ACCEPTED)
            .emailVerificationStatus(EmailVerificationStatus.VERIFIED)
            .twoFactorStatus(TwoFactorGateStatus.DISABLED_BY_POLICY)
            .build();

    assertThat(service.calculateAccessGate(invite)).isEqualTo(AccountAccessGateStatus.READY);
  }

  // --- two-factor gate transitions ---

  @Test
  void markTwoFactorActive_Should_transitionPendingInvitesToActive() {
    AccountInvite invite =
        AccountInvite.builder()
            .acceptedByUserId("user-1")
            .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
            .build();
    when(accountInviteRepository.findAllByAcceptedByUserIdAndTwoFactorStatus(
            "user-1", TwoFactorGateStatus.PENDING_SETUP))
        .thenReturn(List.of(invite));

    service.markTwoFactorActive("user-1");

    assertThat(invite.getTwoFactorStatus()).isEqualTo(TwoFactorGateStatus.ACTIVE);
    verify(accountInviteRepository).saveAll(List.of(invite));
  }

  @Test
  void markTwoFactorPendingSetup_Should_reopenActiveGates() {
    AccountInvite invite =
        AccountInvite.builder()
            .acceptedByUserId("user-1")
            .twoFactorStatus(TwoFactorGateStatus.ACTIVE)
            .build();
    when(accountInviteRepository.findAllByAcceptedByUserIdAndTwoFactorStatus(
            "user-1", TwoFactorGateStatus.ACTIVE))
        .thenReturn(List.of(invite));

    service.markTwoFactorPendingSetup("user-1");

    assertThat(invite.getTwoFactorStatus()).isEqualTo(TwoFactorGateStatus.PENDING_SETUP);
    verify(accountInviteRepository).saveAll(List.of(invite));
  }

  @Test
  void markTwoFactorActive_Should_doNothing_When_userIdBlankOrNoMatches() {
    service.markTwoFactorActive(" ");
    verify(accountInviteRepository, never())
        .findAllByAcceptedByUserIdAndTwoFactorStatus(any(), any());

    when(accountInviteRepository.findAllByAcceptedByUserIdAndTwoFactorStatus(
            "user-2", TwoFactorGateStatus.PENDING_SETUP))
        .thenReturn(List.of());
    service.markTwoFactorActive("user-2");
    verify(accountInviteRepository, never()).saveAll(any());
  }

  @Test
  void waiveTwoFactor_Should_persistWaivedInvite() {
    AccountInvite invite =
        AccountInvite.builder().twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP).build();
    when(authenticatedUser.getUserId()).thenReturn("admin-1");

    service.waiveTwoFactor(invite, new WaiveTwoFactorCommand("four-eyes onboarding"));

    verify(accountInviteRepository).save(invite);
  }

  // --- waiveTwoFactor guards ---

  @Test
  void waiveTwoFactor_Should_throwBadRequest_When_inviteNull() {
    assertThatThrownBy(
            () -> service.waiveTwoFactor((AccountInvite) null, new WaiveTwoFactorCommand("reason")))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void waiveTwoFactor_Should_throwBadRequest_When_commandNull() {
    AccountInvite invite = AccountInvite.builder().build();

    assertThatThrownBy(() -> service.waiveTwoFactor(invite, null))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void waiveTwoFactor_Should_throwBadRequest_When_reasonBlank() {
    AccountInvite invite = AccountInvite.builder().build();

    assertThatThrownBy(() -> service.waiveTwoFactor(invite, new WaiveTwoFactorCommand("  ")))
        .isInstanceOf(BadRequestException.class);
  }

  // --- render / buildAcceptUrl via sendInvite ---

  @Test
  void sendInvite_Should_renderAllPlaceholders_And_stripTrailingSlashes() {
    AccountInvite invite =
        AccountInvite.builder()
            .id(1L)
            .tenantId(9L)
            .recipientEmail("a@example.org")
            .firstName("Ada")
            .lastName("Lovelace")
            .status(AccountInviteStatus.DRAFT)
            .build();
    InviteEmailTemplate template =
        InviteEmailTemplate.builder()
            .id(20L)
            .subject("{{firstName}} {{lastName}} {{email}} {{tenantId}}")
            .body("Link: {{inviteLink}}")
            .build();
    when(accountInviteRepository.findById(1L)).thenReturn(Optional.of(invite));
    when(templateRepository.findById(20L)).thenReturn(Optional.of(template));
    when(accountInviteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(deliveryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.sendInvite(new SendInviteCommand(1L, 20L, "https://app.oriso.org///"));

    assertThat(result.delivery().getSubjectSnapshot()).isEqualTo("Ada Lovelace a@example.org 9");
    assertThat(result.acceptUrl()).startsWith("https://app.oriso.org/").doesNotContain("///");
  }

  @Test
  void sendInvite_Should_useDefaultAcceptUrl_When_baseUrlBlank() {
    AccountInvite invite =
        AccountInvite.builder()
            .id(1L)
            .recipientEmail("a@example.org")
            .status(AccountInviteStatus.DRAFT)
            .build();
    InviteEmailTemplate template =
        InviteEmailTemplate.builder().id(20L).subject("s").body("{{inviteLink}}").build();
    when(accountInviteRepository.findById(1L)).thenReturn(Optional.of(invite));
    when(templateRepository.findById(20L)).thenReturn(Optional.of(template));
    when(accountInviteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(deliveryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.sendInvite(new SendInviteCommand(1L, 20L, "   "));

    assertThat(result.acceptUrl()).startsWith("/account-invite/");
  }

  @Test
  void render_Should_returnEmptyString_When_templateValueNull() {
    AccountInvite invite =
        AccountInvite.builder()
            .id(1L)
            .recipientEmail("a@example.org")
            .status(AccountInviteStatus.DRAFT)
            .build();
    InviteEmailTemplate template =
        InviteEmailTemplate.builder().id(20L).subject(null).body(null).build();
    when(accountInviteRepository.findById(1L)).thenReturn(Optional.of(invite));
    when(templateRepository.findById(20L)).thenReturn(Optional.of(template));
    when(accountInviteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(deliveryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.sendInvite(new SendInviteCommand(1L, 20L, "https://x"));

    assertThat(result.delivery().getSubjectSnapshot()).isEmpty();
    assertThat(result.delivery().getBodySnapshot()).isEmpty();
  }

  // --- hash() determinism ---

  @Test
  void hash_Should_beDeterministic_forSameInput() {
    assertThat(AccountInviteService.hash("same-token"))
        .isEqualTo(AccountInviteService.hash("same-token"));
  }

  @Test
  void hash_Should_differ_forDifferentInput() {
    assertThat(AccountInviteService.hash("token-a"))
        .isNotEqualTo(AccountInviteService.hash("token-b"));
  }

  @Test
  void createInvite_Should_ThrowBadRequest_When_TenantAdminTenantIdMatchesExistingTenant() {
    when(tenantService.getRestrictedTenantData(7L))
        .thenReturn(new RestrictedTenantDTO().name("Existing Tenant"));

    var command =
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.TENANT_ADMIN,
            7L,
            "owner@example.org",
            "New",
            "Owner",
            null,
            null,
            30L);

    assertThatThrownBy(() -> service.createInvite(command)).isInstanceOf(ConflictException.class);
    verify(accountInviteRepository, never()).save(any());
  }

  @Test
  void createInvite_Should_ThrowConflict_When_TenantAdminTenantIdUsedByActiveInvite() {
    when(tenantService.getRestrictedTenantData(7L))
        .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
    when(accountInviteRepository.existsByTenantIdAndTargetRoleAndStatusIn(
            7L,
            AccountInviteTargetRole.TENANT_ADMIN,
            List.of(AccountInviteStatus.DRAFT, AccountInviteStatus.EMAIL_SENT)))
        .thenReturn(true);

    var command =
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.TENANT_ADMIN,
            7L,
            "owner@example.org",
            "New",
            "Owner",
            null,
            null,
            30L);

    assertThatThrownBy(() -> service.createInvite(command)).isInstanceOf(ConflictException.class);
    verify(accountInviteRepository, never()).save(any());
  }

  @Test
  void createInvite_Should_Succeed_When_TenantIdOnlyUsedByTerminalInvite() {
    when(tenantService.getRestrictedTenantData(7L))
        .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
    when(accountInviteRepository.existsByTenantIdAndTargetRoleAndStatusIn(
            7L,
            AccountInviteTargetRole.TENANT_ADMIN,
            List.of(AccountInviteStatus.DRAFT, AccountInviteStatus.EMAIL_SENT)))
        .thenReturn(false);
    when(authenticatedUser.getUserId()).thenReturn("admin-1");
    when(authenticatedUser.getUsername()).thenReturn("admin@example.org");
    when(accountInviteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var command =
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.TENANT_ADMIN,
            7L,
            "owner@example.org",
            "New",
            "Owner",
            null,
            null,
            30L);

    AccountInvite invite = service.createInvite(command);

    assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
    assertThat(invite.getTenantId()).isEqualTo(7L);
  }

  @Test
  void createInvite_Should_Succeed_When_NonTenantAdminRoleReusesCollidingTenantId() {
    when(authenticatedUser.getUserId()).thenReturn("admin-1");
    when(authenticatedUser.getUsername()).thenReturn("admin@example.org");
    when(accountInviteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var command =
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.COUNSELLOR,
            7L,
            "counsellor@example.org",
            "New",
            "Counsellor",
            null,
            null,
            30L);

    AccountInvite invite = service.createInvite(command);

    assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
    verify(tenantService, never()).getRestrictedTenantData(any(Long.class));
    verify(accountInviteRepository, never())
        .existsByTenantIdAndTargetRoleAndStatusIn(any(), any(), any());
  }

  @Test
  void createInvite_Should_Succeed_When_TenantAdminTenantIdIsFree() {
    when(tenantService.getRestrictedTenantData(9L))
        .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
    when(accountInviteRepository.existsByTenantIdAndTargetRoleAndStatusIn(
            9L,
            AccountInviteTargetRole.TENANT_ADMIN,
            List.of(AccountInviteStatus.DRAFT, AccountInviteStatus.EMAIL_SENT)))
        .thenReturn(false);
    when(authenticatedUser.getUserId()).thenReturn("admin-1");
    when(authenticatedUser.getUsername()).thenReturn("admin@example.org");
    when(accountInviteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var command =
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.TENANT_ADMIN,
            9L,
            "owner2@example.org",
            "New",
            "Owner",
            null,
            null,
            30L);

    AccountInvite invite = service.createInvite(command);

    assertThat(invite.getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
    assertThat(invite.getTenantId()).isEqualTo(9L);
  }

  // ---------------------------------------------------------------------------
  // TEN-INV-U3 — tenant/agency ID reservation orchestration (#889)
  // ---------------------------------------------------------------------------

  private void givenAdminAndPassthroughSave() {
    when(authenticatedUser.getUserId()).thenReturn("admin-1");
    when(authenticatedUser.getUsername()).thenReturn("admin@example.org");
    when(accountInviteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  private void givenTenantIdFreeLocally(long tenantId) {
    when(tenantService.getRestrictedTenantData(tenantId))
        .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
    when(accountInviteRepository.existsByTenantIdAndTargetRoleAndStatusIn(
            tenantId,
            AccountInviteTargetRole.TENANT_ADMIN,
            List.of(AccountInviteStatus.DRAFT, AccountInviteStatus.EMAIL_SENT)))
        .thenReturn(false);
  }

  @Test
  void createInvite_Should_ReserveTenantIdAndKeepToken_When_TenantAdminManualId() {
    givenAdminAndPassthroughSave();
    givenTenantIdFreeLocally(21L);
    when(tenantIdAllocationClient.reserve(21L))
        .thenReturn(new TenantIdReservation(21L, "res-token-21"));
    when(tenantIdAllocationClient.getAvailability(21L)).thenReturn(IdAllocationStatus.RESERVED);

    AccountInvite invite =
        service.createInvite(
            new CreateAccountInviteCommand(
                AccountInviteTargetRole.TENANT_ADMIN,
                21L,
                "owner@example.org",
                "New",
                "Owner",
                null,
                null,
                30L,
                IdAllocationMode.MANUAL,
                null));

    assertThat(invite.getTenantId()).isEqualTo(21L);
    assertThat(invite.getTenantIdReservationToken()).isEqualTo("res-token-21");
    verify(tenantIdAllocationClient, never()).release(anyLong());
  }

  @Test
  void createInvite_Should_AutoReserveSmallestFreeTenantId_When_TenantAdminWithoutTenantId() {
    givenAdminAndPassthroughSave();
    when(tenantIdAllocationClient.reserve(null))
        .thenReturn(new TenantIdReservation(36L, "res-token-36"));
    when(tenantIdAllocationClient.getAvailability(36L)).thenReturn(IdAllocationStatus.RESERVED);

    AccountInvite invite =
        service.createInvite(
            new CreateAccountInviteCommand(
                AccountInviteTargetRole.TENANT_ADMIN,
                null,
                "owner@example.org",
                "New",
                "Owner",
                null,
                null,
                30L,
                IdAllocationMode.AUTO,
                null));

    assertThat(invite.getTenantId()).isEqualTo(36L);
    assertThat(invite.getTenantIdReservationToken()).isEqualTo("res-token-36");
  }

  @Test
  void createInvite_Should_PropagateConflictWithoutSaving_When_TenantIdReservationConflicts() {
    givenTenantIdFreeLocally(21L);
    when(tenantIdAllocationClient.reserve(21L))
        .thenThrow(new ConflictException("tenantId 21 is already assigned or reserved"));

    var command =
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.TENANT_ADMIN,
            21L,
            "owner@example.org",
            null,
            null,
            null,
            null,
            null,
            IdAllocationMode.MANUAL,
            null);

    assertThatThrownBy(() -> service.createInvite(command)).isInstanceOf(ConflictException.class);
    verify(accountInviteRepository, never()).save(any());
    verify(tenantIdAllocationClient, never()).release(anyLong());
  }

  @Test
  void createInvite_Should_ReleaseReservation_When_RevalidationBeforeSaveFindsIdNotReserved() {
    givenTenantIdFreeLocally(21L);
    when(tenantIdAllocationClient.reserve(21L))
        .thenReturn(new TenantIdReservation(21L, "res-token-21"));
    when(tenantIdAllocationClient.getAvailability(21L)).thenReturn(IdAllocationStatus.ASSIGNED);

    var command =
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.TENANT_ADMIN,
            21L,
            "owner@example.org",
            null,
            null,
            null,
            null,
            null,
            IdAllocationMode.MANUAL,
            null);

    assertThatThrownBy(() -> service.createInvite(command)).isInstanceOf(ConflictException.class);
    verify(accountInviteRepository, never()).save(any());
    verify(tenantIdAllocationClient).release(21L);
  }

  @Test
  void createInvite_Should_ReleaseReservation_When_SavingTheInviteFails() {
    when(authenticatedUser.getUserId()).thenReturn("admin-1");
    when(authenticatedUser.getUsername()).thenReturn("admin@example.org");
    givenTenantIdFreeLocally(21L);
    when(tenantIdAllocationClient.reserve(21L))
        .thenReturn(new TenantIdReservation(21L, "res-token-21"));
    when(tenantIdAllocationClient.getAvailability(21L)).thenReturn(IdAllocationStatus.RESERVED);
    when(accountInviteRepository.save(any())).thenThrow(new IllegalStateException("db down"));

    var command =
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.TENANT_ADMIN,
            21L,
            "owner@example.org",
            null,
            null,
            null,
            null,
            null,
            IdAllocationMode.MANUAL,
            null);

    assertThatThrownBy(() -> service.createInvite(command))
        .isInstanceOf(IllegalStateException.class);
    verify(tenantIdAllocationClient).release(21L);
  }

  @Test
  void createInvite_Should_ReserveAgencyIdInAgencyServiceIdSpace_When_AgencyAllocationRequested() {
    givenAdminAndPassthroughSave();
    when(tenantIdAllocationClient.reserve(null))
        .thenReturn(new TenantIdReservation(36L, "res-token-36"));
    when(tenantIdAllocationClient.getAvailability(36L)).thenReturn(IdAllocationStatus.RESERVED);
    when(agencyIdAllocationClient.reserve(null, 36L)).thenReturn(5L);
    when(agencyIdAllocationClient.getAvailability(5L)).thenReturn(IdAllocationStatus.RESERVED);

    AccountInvite invite =
        service.createInvite(
            new CreateAccountInviteCommand(
                AccountInviteTargetRole.TENANT_ADMIN,
                null,
                "owner@example.org",
                null,
                null,
                null,
                null,
                null,
                IdAllocationMode.AUTO,
                IdAllocationMode.AUTO));

    assertThat(invite.getTenantId()).isEqualTo(36L);
    assertThat(invite.getAgencyId()).isEqualTo(5L);
    verify(agencyIdAllocationClient).reserve(null, 36L);
  }

  @Test
  void createInvite_Should_ReleaseTenantReservation_When_AgencyReservationConflicts() {
    givenTenantIdFreeLocally(21L);
    when(tenantIdAllocationClient.reserve(21L))
        .thenReturn(new TenantIdReservation(21L, "res-token-21"));
    when(agencyIdAllocationClient.reserve(9L, 21L))
        .thenThrow(new ConflictException("agencyId 9 is already assigned or reserved"));

    var command =
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.TENANT_ADMIN,
            21L,
            "owner@example.org",
            null,
            null,
            9L,
            null,
            null,
            IdAllocationMode.MANUAL,
            IdAllocationMode.MANUAL);

    assertThatThrownBy(() -> service.createInvite(command)).isInstanceOf(ConflictException.class);
    verify(tenantIdAllocationClient).release(21L);
    verify(agencyIdAllocationClient, never()).release(anyLong());
    verify(accountInviteRepository, never()).save(any());
  }

  @Test
  void createInvite_Should_ThrowBadRequest_When_ManualTenantModeWithoutTenantId() {
    var command =
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.TENANT_ADMIN,
            null,
            "owner@example.org",
            null,
            null,
            null,
            null,
            null,
            IdAllocationMode.MANUAL,
            null);

    assertThatThrownBy(() -> service.createInvite(command)).isInstanceOf(BadRequestException.class);
    verify(tenantIdAllocationClient, never()).reserve(any());
  }

  @Test
  void createInvite_Should_ThrowBadRequest_When_AutoTenantModeWithPinnedTenantId() {
    var command =
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.TENANT_ADMIN,
            21L,
            "owner@example.org",
            null,
            null,
            null,
            null,
            null,
            IdAllocationMode.AUTO,
            null);

    assertThatThrownBy(() -> service.createInvite(command)).isInstanceOf(BadRequestException.class);
    verify(tenantIdAllocationClient, never()).reserve(any());
  }

  @Test
  void createInvite_Should_NotTouchAllocationServices_When_NonTenantAdminWithoutAgencyMode() {
    givenAdminAndPassthroughSave();

    service.createInvite(
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.COUNSELLOR,
            7L,
            "counsellor@example.org",
            null,
            null,
            3L,
            null,
            null,
            null,
            null));

    verifyNoInteractions(tenantIdAllocationClient);
    verifyNoInteractions(agencyIdAllocationClient);
  }

  @Test
  void resendInvite_Should_CarryReservationTokenToReplacementInvite() {
    AccountInvite oldInvite =
        AccountInvite.builder()
            .id(10L)
            .tenantId(21L)
            .tenantIdReservationToken("res-token-21")
            .recipientEmail("owner@example.org")
            .targetRole(AccountInviteTargetRole.TENANT_ADMIN)
            .status(AccountInviteStatus.EMAIL_SENT)
            .build();
    InviteEmailTemplate template =
        InviteEmailTemplate.builder()
            .id(20L)
            .kind(InviteEmailTemplateKind.TENANT_INVITE)
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

    assertThat(result.invite().getTenantIdReservationToken()).isEqualTo("res-token-21");
    assertThat(result.invite().getTenantId()).isEqualTo(21L);
  }
}
