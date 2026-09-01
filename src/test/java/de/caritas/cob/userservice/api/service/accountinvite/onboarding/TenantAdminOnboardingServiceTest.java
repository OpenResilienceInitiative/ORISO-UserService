package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityProfile;
import de.caritas.cob.userservice.api.port.out.IdentityProfileLookup;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteLinkException;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.OperatorDpaContentClient.OperatorDpa;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantAdminOnboardingService.RegisterTenantAdminCommand;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.MultilingualTenantDTO;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class TenantAdminOnboardingServiceTest {

  private static final String RAW_TOKEN = "raw-invite-token";
  private static final String TOKEN_HASH = AccountInviteService.hash(RAW_TOKEN);
  private static final String RESERVATION_TOKEN = "3f2c6d1e-8b1a-4b8e-9f47-1234567890ab";
  private static final String OPERATOR_DPA_JSON =
      "{\"de\":\"<h2>Auftragsverarbeitung</h2>\",\"en\":\"<h2>Data processing</h2>\"}";
  private static final Long RESERVED_TENANT_ID = 21L;

  @Mock private AccountInviteRepository accountInviteRepository;
  @Mock private AccountInviteService accountInviteService;
  @Mock private CreateAdminService createAdminService;
  @Mock private IdentityClient identityClient;
  @Mock private IdentitySecondFactor identitySecondFactor;
  @Mock private IdentityAccountRemover identityAccountRemover;
  @Mock private IdentityProfileLookup identityProfileLookup;
  @Mock private TenantCreationClient tenantCreationClient;
  @Mock private OperatorDpaContentClient operatorDpaContentClient;
  @Mock private PublicDpaForwardClient publicDpaForwardClient;

  @Mock
  private de.caritas.cob.userservice.api.service.accountinvite.DpaForwardEmailService
      dpaForwardEmailService;

  /**
   * The read paths drive their short database-only transactions through a {@link
   * TransactionTemplate} (#1008 review) instead of {@code @Transactional}. A mocked manager hands
   * out a null status, which the template treats as an ordinary transaction: the callback runs,
   * exceptions propagate.
   */
  @Mock private PlatformTransactionManager transactionManager;

  private TenantAdminOnboardingService service;

  @BeforeEach
  void setUp() {
    service =
        new TenantAdminOnboardingService(
            accountInviteRepository,
            accountInviteService,
            createAdminService,
            identityClient,
            identitySecondFactor,
            identityAccountRemover,
            identityProfileLookup,
            tenantCreationClient,
            operatorDpaContentClient,
            publicDpaForwardClient,
            dpaForwardEmailService,
            new UsernameTranscoder(),
            transactionManager);
    // the real service resolves a path-only link against the configured App origin; the default
    // here passes an already-absolute link straight through, as production does
    lenient()
        .when(dpaForwardEmailService.toAbsoluteSignLink(anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  private static AccountInvite tenantAdminInvite(AccountInviteStatus status) {
    return AccountInvite.builder()
        .id(7L)
        .targetRole(AccountInviteTargetRole.TENANT_ADMIN)
        .tenantId(RESERVED_TENANT_ID)
        .tenantIdReservationToken(RESERVATION_TOKEN)
        .recipientEmail("tenant.admin@example.org")
        .firstName("Erika")
        .lastName("Beispiel")
        .status(status)
        .emailVerificationStatus(EmailVerificationStatus.PENDING)
        .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
        .expiresAt(LocalDateTime.now().plusDays(10))
        .createDate(LocalDateTime.now().minusDays(1))
        .build();
  }

  private static final OperatorDpa OPERATOR_DPA =
      new OperatorDpa(OPERATOR_DPA_JSON, "2026-07-20T10:00");

  /** The governing operator DPA the invitee is shown and accepts (#569). */
  private void givenPublishedOperatorDpa() {
    when(operatorDpaContentClient.fetchPublishedDpa()).thenReturn(OPERATOR_DPA);
  }

  private static RegisterTenantAdminCommand validCommand() {
    return new RegisterTenantAdminCommand(
        "Beispiel gGmbH",
        "beispiel",
        "Musterstrasse 1, 12345 Musterstadt",
        true,
        "Erika Beispiel",
        "CEO",
        "tenant.admin@example.org",
        "Beispiel gGmbH",
        "s3cretPassword",
        RESERVED_TENANT_ID,
        RESERVATION_TOKEN);
  }

  // --- resolve ---

  @Test
  void resolveOnboardingInvite_blankToken_throwsBadRequest() {
    assertThrows(BadRequestException.class, () -> service.resolveOnboardingInvite(" "));
  }

  @Test
  void resolveOnboardingInvite_unknownToken_throwsNotFound() {
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.resolveOnboardingInvite(RAW_TOKEN));
  }

  @Test
  void resolveOnboardingInvite_nonTenantAdminInvite_throwsNotFound() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    invite.setTargetRole(AccountInviteTargetRole.COUNSELLOR);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));

    assertThrows(NotFoundException.class, () -> service.resolveOnboardingInvite(RAW_TOKEN));
  }

  @Test
  void resolveOnboardingInvite_deliverableInvite_returnsPlainState() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));

    var state = service.resolveOnboardingInvite(RAW_TOKEN);

    assertEquals(invite, state.invite());
    assertFalse(state.pendingTwoFactorResume());
  }

  @Test
  void resolveOnboardingInvite_deliverableInvite_carriesTheOperatorDpaText() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(operatorDpaContentClient.fetchPublishedDpaContent()).thenReturn(OPERATOR_DPA_JSON);

    var state = service.resolveOnboardingInvite(RAW_TOKEN);

    assertEquals(OPERATOR_DPA_JSON, state.dpaContent());
  }

  @Test
  void resolveOnboardingInvite_deliverableInviteWithoutPublishedDpa_resolvesWithoutText() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(operatorDpaContentClient.fetchPublishedDpaContent()).thenReturn(null);

    var state = service.resolveOnboardingInvite(RAW_TOKEN);

    assertEquals(invite, state.invite());
    assertNull(state.dpaContent());
  }

  @Test
  void resolveOnboardingInvite_expiredDeliverableInvite_marksExpiredAndThrows() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    invite.setExpiresAt(LocalDateTime.now().minusMinutes(5));
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));

    var exception =
        assertThrows(
            AccountInviteLinkException.class, () -> service.resolveOnboardingInvite(RAW_TOKEN));

    assertEquals(AccountInviteLinkException.Reason.EXPIRED, exception.getReason());
    assertEquals(AccountInviteStatus.EXPIRED, invite.getStatus());
    verify(accountInviteRepository).save(invite);
  }

  @Test
  void resolveOnboardingInvite_consumedWithPendingTwoFactor_returnsResumeState() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.ACCEPTED);
    invite.setTotpPendingSecret("STOREDSECRET");
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));

    var state = service.resolveOnboardingInvite(RAW_TOKEN);

    assertTrue(state.pendingTwoFactorResume());
    assertEquals("STOREDSECRET", state.invite().getTotpPendingSecret());
    // The resume path re-enters at the 2FA step, which shows no contract — no upstream lookup.
    assertNull(state.dpaContent());
    verify(operatorDpaContentClient, never()).fetchPublishedDpaContent();
  }

  @Test
  void resolveOnboardingInvite_consumedWithSatisfiedGate_throwsConsumed() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.ACCEPTED);
    invite.setTwoFactorStatus(TwoFactorGateStatus.ACTIVE);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));

    var exception =
        assertThrows(
            AccountInviteLinkException.class, () -> service.resolveOnboardingInvite(RAW_TOKEN));

    assertEquals(AccountInviteLinkException.Reason.CONSUMED, exception.getReason());
  }

  @Test
  void resolveOnboardingInvite_revokedInvite_throwsRevoked() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.REVOKED);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));

    var exception =
        assertThrows(
            AccountInviteLinkException.class, () -> service.resolveOnboardingInvite(RAW_TOKEN));

    assertEquals(AccountInviteLinkException.Reason.REVOKED, exception.getReason());
  }

  // --- register ---

  @Test
  void registerTenantAdmin_happyPath_createsAdminAndTenantAndReturnsTotpMaterial() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    givenPublishedOperatorDpa();
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(accountInviteRepository.claimForAcceptance(eq(7L), isNull(), any())).thenReturn(1);
    when(accountInviteRepository.findById(7L)).thenReturn(Optional.of(invite));
    Admin admin =
        Admin.builder()
            .id("kc-user-1")
            .username("tenant.admin@example.org")
            .email("tenant.admin@example.org")
            .firstName("Erika")
            .lastName("Beispiel")
            .build();
    when(createAdminService.createNewTenantAdmin(any())).thenReturn(admin);
    when(identitySecondFactor.getOtpCredential(anyString()))
        .thenReturn(new IdentityOtpCredential(null, "TOTPSECRET", "QRBASE64", null));
    when(tenantCreationClient.createTenant(any()))
        .thenReturn(new MultilingualTenantDTO().id(RESERVED_TENANT_ID));

    var result = service.registerTenantAdmin(RAW_TOKEN, validCommand());

    assertEquals(RESERVED_TENANT_ID, result.tenantId());
    assertEquals("TOTPSECRET", result.totpSecret());
    assertEquals("QRBASE64", result.totpQrCodeBase64());

    ArgumentCaptor<CreateAdminDTO> adminCaptor = ArgumentCaptor.forClass(CreateAdminDTO.class);
    verify(createAdminService).createNewTenantAdmin(adminCaptor.capture());
    assertEquals("tenant.admin@example.org", adminCaptor.getValue().getUsername());
    assertEquals("tenant.admin@example.org", adminCaptor.getValue().getEmail());
    assertEquals("s3cretPassword", adminCaptor.getValue().getPassword());
    assertEquals(21, adminCaptor.getValue().getTenantId());

    ArgumentCaptor<MultilingualTenantDTO> tenantCaptor =
        ArgumentCaptor.forClass(MultilingualTenantDTO.class);
    verify(tenantCreationClient).createTenant(tenantCaptor.capture());
    assertEquals(RESERVED_TENANT_ID, tenantCaptor.getValue().getId());
    assertEquals("Beispiel gGmbH", tenantCaptor.getValue().getName());
    assertEquals("beispiel", tenantCaptor.getValue().getSubdomain());
    assertEquals(RESERVATION_TOKEN, tenantCaptor.getValue().getTenantIdReservationToken());
    assertTrue(tenantCaptor.getValue().getAdminEmails().contains("tenant.admin@example.org"));

    assertEquals("kc-user-1", invite.getAcceptedByUserId());
    assertEquals("TOTPSECRET", invite.getTotpPendingSecret());
    verify(accountInviteRepository).save(invite);
  }

  /**
   * The onboarding flow never sent a licensing block, so every tenant created through an invite
   * link landed with a null consultant allowance: {@code Licensing.allowedNumberOfUsers} is a
   * required property of the TenantService creation contract and {@code licensing_allowed_users} is
   * a nullable column without a default, so the Admin tenant list showed an empty "Max. erlaubte
   * Berater" column for those tenants. A tenant must never be created without an allowance.
   */
  @Test
  void registerTenantAdmin_alwaysSendsANonNullConsultantAllowance() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    givenPublishedOperatorDpa();
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(accountInviteRepository.claimForAcceptance(eq(7L), isNull(), any())).thenReturn(1);
    when(accountInviteRepository.findById(7L)).thenReturn(Optional.of(invite));
    when(createAdminService.createNewTenantAdmin(any())).thenReturn(onboardedAdmin());
    when(identitySecondFactor.getOtpCredential(anyString()))
        .thenReturn(new IdentityOtpCredential(null, "TOTPSECRET", null, null));
    when(tenantCreationClient.createTenant(any()))
        .thenReturn(new MultilingualTenantDTO().id(RESERVED_TENANT_ID));

    service.registerTenantAdmin(RAW_TOKEN, validCommand());

    ArgumentCaptor<MultilingualTenantDTO> tenantCaptor =
        ArgumentCaptor.forClass(MultilingualTenantDTO.class);
    verify(tenantCreationClient).createTenant(tenantCaptor.capture());
    var licensing = tenantCaptor.getValue().getLicensing();
    assertNotNull(licensing, "the created tenant carries no licensing block");
    assertNotNull(
        licensing.getAllowedNumberOfUsers(),
        "the created tenant carries no allowed number of consultants");
    assertEquals(
        9999,
        licensing.getAllowedNumberOfUsers(),
        "the provisional allowance must match the Admin panel's default of 9999");
  }

  /**
   * #569 defect 2 (compliance): the acceptance must become an auditable U9 admin signature for the
   * tenant being created — signer identity, the operator DPA version actually shown and the form
   * data — instead of a log line. Defect 1 follows from it: with that signature the tenant's DPA
   * status resolves to VALID, so the first login is not blocked.
   */
  @Test
  void registerTenantAdmin_recordsTheDpaAcceptanceAsTheTenantsAdminSignature() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    givenPublishedOperatorDpa();
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(accountInviteRepository.claimForAcceptance(eq(7L), isNull(), any())).thenReturn(1);
    when(accountInviteRepository.findById(7L)).thenReturn(Optional.of(invite));
    when(createAdminService.createNewTenantAdmin(any())).thenReturn(onboardedAdmin());
    when(identitySecondFactor.getOtpCredential(anyString()))
        .thenReturn(new IdentityOtpCredential(null, "TOTPSECRET", null, null));
    when(tenantCreationClient.createTenant(any()))
        .thenReturn(new MultilingualTenantDTO().id(RESERVED_TENANT_ID));

    service.registerTenantAdmin(RAW_TOKEN, validCommand());

    ArgumentCaptor<MultilingualTenantDTO> tenantCaptor =
        ArgumentCaptor.forClass(MultilingualTenantDTO.class);
    verify(tenantCreationClient).createTenant(tenantCaptor.capture());
    var acceptance = tenantCaptor.getValue().getOnboardingDpaAcceptance();
    assertNotNull(acceptance);
    assertTrue(acceptance.getAccepted());
    assertEquals("kc-user-1", acceptance.getSignerUserId());
    assertEquals("tenant.admin@example.org", acceptance.getSignerUsername());
    assertEquals("Erika Beispiel", acceptance.getSignerName());
    assertEquals("CEO", acceptance.getSignerPosition());
    assertEquals("tenant.admin@example.org", acceptance.getSignerEmail());
    assertEquals("Beispiel gGmbH", acceptance.getSignerOrganisation());
    assertEquals("2026-07-20T10:00", acceptance.getDpaVersion());
  }

  /**
   * No governing DPA published means there is nothing the invitee could validly accept, and the
   * tenant would be created straight into the non-actionable blocker. The registration is refused
   * BEFORE the single-use link is claimed, so it stays usable once the operator publishes.
   */
  @Test
  void registerTenantAdmin_noOperatorDpaPublished_refusesBeforeConsumingAnything() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(operatorDpaContentClient.fetchPublishedDpa()).thenReturn(null);

    assertThrows(
        InternalServerErrorException.class,
        () -> service.registerTenantAdmin(RAW_TOKEN, validCommand()));

    verify(accountInviteRepository, never()).claimForAcceptance(anyLong(), any(), any());
    verify(createAdminService, never()).createNewTenantAdmin(any());
    verify(tenantCreationClient, never()).createTenant(any());
  }

  /** A signature the invitee left nameless still names a human: the invited admin. */
  @Test
  void registerTenantAdmin_blankSignerName_fallsBackToTheOnboardedAdminsName() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    givenPublishedOperatorDpa();
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(accountInviteRepository.claimForAcceptance(eq(7L), isNull(), any())).thenReturn(1);
    when(accountInviteRepository.findById(7L)).thenReturn(Optional.of(invite));
    when(createAdminService.createNewTenantAdmin(any())).thenReturn(onboardedAdmin());
    when(identitySecondFactor.getOtpCredential(anyString()))
        .thenReturn(new IdentityOtpCredential(null, "TOTPSECRET", null, null));
    when(tenantCreationClient.createTenant(any()))
        .thenReturn(new MultilingualTenantDTO().id(RESERVED_TENANT_ID));
    var command =
        new RegisterTenantAdminCommand(
            "Beispiel gGmbH",
            "beispiel",
            null,
            true,
            "  ",
            null,
            null,
            null,
            "s3cretPassword",
            RESERVED_TENANT_ID,
            RESERVATION_TOKEN);

    service.registerTenantAdmin(RAW_TOKEN, command);

    ArgumentCaptor<MultilingualTenantDTO> tenantCaptor =
        ArgumentCaptor.forClass(MultilingualTenantDTO.class);
    verify(tenantCreationClient).createTenant(tenantCaptor.capture());
    assertEquals(
        "Erika Beispiel", tenantCaptor.getValue().getOnboardingDpaAcceptance().getSignerName());
  }

  private static Admin onboardedAdmin() {
    return Admin.builder()
        .id("kc-user-1")
        .username("tenant.admin@example.org")
        .email("tenant.admin@example.org")
        .firstName("Erika")
        .lastName("Beispiel")
        .build();
  }

  private static RegisterTenantAdminCommand commandWithoutAcceptance() {
    return new RegisterTenantAdminCommand(
        "Beispiel gGmbH",
        "beispiel",
        null,
        false,
        null,
        null,
        null,
        null,
        "s3cretPassword",
        RESERVED_TENANT_ID,
        RESERVATION_TOKEN);
  }

  @Test
  void registerTenantAdmin_dpaNeitherAcceptedNorForwarded_throwsBadRequest() {
    // an invite that never forwarded cannot register without accepting — the client's word alone
    // is not enough (ORISO-Admin#722)
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH))
        .thenReturn(Optional.of(tenantAdminInvite(AccountInviteStatus.EMAIL_SENT)));

    assertThrows(
        BadRequestException.class,
        () -> service.registerTenantAdmin(RAW_TOKEN, commandWithoutAcceptance()));
    verify(tenantCreationClient, never()).createTenant(any());
  }

  @Test
  void registerTenantAdmin_dpaForwardedEarlier_createsTenantWithoutAcceptance() {
    // given an invite that forwarded the DPA in wizard step 1
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    invite.setDpaForwardedAt(LocalDateTime.now().minusMinutes(5));
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(accountInviteRepository.claimForAcceptance(eq(7L), isNull(), any())).thenReturn(1);
    when(accountInviteRepository.findById(7L)).thenReturn(Optional.of(invite));
    when(createAdminService.createNewTenantAdmin(any(CreateAdminDTO.class)))
        .thenReturn(onboardedAdmin());
    when(identitySecondFactor.getOtpCredential(anyString()))
        .thenReturn(new IdentityOtpCredential(null, "TOTPSECRET", "QRBASE64", null));
    when(tenantCreationClient.createTenant(any()))
        .thenReturn(new MultilingualTenantDTO().id(RESERVED_TENANT_ID));

    // when
    var result = service.registerTenantAdmin(RAW_TOKEN, commandWithoutAcceptance());

    // then: the tenant is created, but WITHOUT an acceptance signature — the DPA stays pending
    assertEquals(RESERVED_TENANT_ID, result.tenantId());
    var captor = ArgumentCaptor.forClass(MultilingualTenantDTO.class);
    verify(tenantCreationClient).createTenant(captor.capture());
    assertNull(captor.getValue().getOnboardingDpaAcceptance());
    // no operator DPA lookup is needed when nothing is accepted
    verify(operatorDpaContentClient, never()).fetchPublishedDpa();
  }

  // --- DPA forward from the wizard (ORISO-Admin#722) ---

  private static de.caritas.cob.userservice.tenantservice.generated.web.model.DpaSignInviteDTO
      signInvite() {
    return new de.caritas.cob.userservice.tenantservice.generated.web.model.DpaSignInviteDTO()
        .token("RAWSIGNTOKEN")
        .signLink("https://app.oriso.org/dpa-sign/RAWSIGNTOKEN")
        .expiresAt("2026-08-29T14:31:07");
  }

  @Test
  void forwardDpa_recordsTheForwardAndSendsTheMail_When_aRecipientIsGiven() {
    // given
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(publicDpaForwardClient.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .thenReturn(signInvite());

    // when
    var result = service.forwardDpa(RAW_TOKEN, "legal@example.org");

    // then
    assertEquals("https://app.oriso.org/dpa-sign/RAWSIGNTOKEN", result.signUrl());
    assertEquals("2026-08-29T14:31:07", result.expiresAt());
    // the forward is proven server-side, which is what unlocks registration without acceptance
    assertNotNull(invite.getDpaForwardedAt());
    // two short transactions write: the attempt reservation before the mint, the proof after it
    verify(accountInviteRepository, times(2)).save(invite);
    var captor =
        ArgumentCaptor.forClass(
            de.caritas.cob.userservice.api.service.accountinvite.DpaForwardEmailService
                .DpaForwardEmailCommand.class);
    verify(dpaForwardEmailService).sendSigningLink(captor.capture());
    assertEquals("legal@example.org", captor.getValue().recipientEmail());
    assertEquals(RESERVED_TENANT_ID, captor.getValue().tenantId());
    assertEquals("https://app.oriso.org/dpa-sign/RAWSIGNTOKEN", captor.getValue().signLink());
  }

  @Test
  void forwardDpa_repaysTheReservedAttempt_When_theProviderAnswersWithoutAUsableExpiry() {
    // A 200 that carries no usable link leaves the caller with nothing, exactly like an outage or
    // a throttle — charging the attempt would let three malformed answers exhaust the invitation's
    // five forwards for good.
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(publicDpaForwardClient.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .thenReturn(
            new de.caritas.cob.userservice.tenantservice.generated.web.model.DpaSignInviteDTO()
                .token("RAWSIGNTOKEN")
                .signLink("https://app.oriso.org/dpa-sign/RAWSIGNTOKEN")
                .expiresAt(null));

    assertThrows(
        InternalServerErrorException.class,
        () -> service.forwardDpa(RAW_TOKEN, "legal@example.org"));

    // reserved (+1) and repaid (-1) — the budget is untouched
    assertEquals(0, invite.getDpaForwardCount());
    assertNull(invite.getDpaForwardedAt());
    // and no mail went out for a link that does not exist
    verify(dpaForwardEmailService, never()).sendSigningLink(any());
  }

  @Test
  void forwardDpa_repaysTheReservedAttempt_When_theProviderAnswersWithNoBodyAtAll() {
    // A null DTO is the second way a 200 can carry no link; it must be compensated like the
    // missing expiry, otherwise a provider fault still burns the invitation's budget.
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(publicDpaForwardClient.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .thenReturn(null);

    assertThrows(RuntimeException.class, () -> service.forwardDpa(RAW_TOKEN, "legal@example.org"));

    assertEquals(0, invite.getDpaForwardCount());
    assertNull(invite.getDpaForwardedAt());
    verify(dpaForwardEmailService, never()).sendSigningLink(any());
  }

  @Test
  void forwardDpa_repaysTheReservedAttempt_When_theProviderLinkFailsTheOriginGuard() {
    // The third way: a 200 with a link from a foreign origin. toAbsoluteSignLink rejects it, and
    // the caller is left without a usable link — so the attempt is repaid like the other two.
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(publicDpaForwardClient.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .thenReturn(
            new de.caritas.cob.userservice.tenantservice.generated.web.model.DpaSignInviteDTO()
                .token("RAWSIGNTOKEN")
                .signLink("https://evil.example/dpa-sign/RAWSIGNTOKEN")
                .expiresAt("2026-08-29T14:31:07"));
    when(dpaForwardEmailService.toAbsoluteSignLink("https://evil.example/dpa-sign/RAWSIGNTOKEN"))
        .thenThrow(new BadRequestException("signLink must use the configured ORISO App origin"));

    assertThrows(
        BadRequestException.class, () -> service.forwardDpa(RAW_TOKEN, "legal@example.org"));

    assertEquals(0, invite.getDpaForwardCount());
    assertNull(invite.getDpaForwardedAt());
    verify(dpaForwardEmailService, never()).sendSigningLink(any());
  }

  @Test
  void forwardDpa_returnsAnAbsoluteLink_When_theProviderEmitsAPathOnlyOne() {
    // On Pre-Dev the TenantService base URL is unset, so the mint answers "/dpa-sign/<token>".
    // Both the recipient-less and the mail-failed branch hand that link to the wizard for MANUAL
    // sharing — a copied relative path carries no origin and never reaches the DPA frontend.
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(publicDpaForwardClient.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .thenReturn(
            new de.caritas.cob.userservice.tenantservice.generated.web.model.DpaSignInviteDTO()
                .token("RAWSIGNTOKEN")
                .signLink("/dpa-sign/RAWSIGNTOKEN")
                .expiresAt("2026-08-29T14:31:07"));
    when(dpaForwardEmailService.toAbsoluteSignLink("/dpa-sign/RAWSIGNTOKEN"))
        .thenReturn("https://app.oriso.org/dpa-sign/RAWSIGNTOKEN");

    var result = service.forwardDpa(RAW_TOKEN, null);

    assertEquals("https://app.oriso.org/dpa-sign/RAWSIGNTOKEN", result.signUrl());
  }

  @Test
  void forwardDpa_returnsTheLinkWithoutSendingMail_When_noRecipientIsGiven() {
    // given
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(publicDpaForwardClient.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .thenReturn(signInvite());

    // when
    var result = service.forwardDpa(RAW_TOKEN, null);

    // then: the wizard shares the link manually
    assertNotNull(result.signUrl());
    assertNotNull(invite.getDpaForwardedAt());
    verify(dpaForwardEmailService, never()).sendSigningLink(any());
  }

  /**
   * Option A: a mail that cannot be delivered must NOT fail the call. The sign link is already
   * minted and live, and every burnt link is capped for 14 days — failing here is what locked the
   * owner out with five unusable links and no mail.
   */
  @Test
  void forwardDpa_returnsTheLinkAndFlagsIt_When_theMailCannotBeSent() {
    // given the mail service rejects the send
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(publicDpaForwardClient.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .thenReturn(signInvite());
    doThrow(new BadRequestException("signLink is invalid"))
        .when(dpaForwardEmailService)
        .sendSigningLink(any());

    // when
    var result = service.forwardDpa(RAW_TOKEN, "legal@example.org");

    // then the caller still gets the link, marked as undelivered
    assertEquals("https://app.oriso.org/dpa-sign/RAWSIGNTOKEN", result.signUrl());
    assertFalse(result.mailSent());
    // and the forward is RECORDED: the link is live, so the proof of it must survive the failure
    assertNotNull(invite.getDpaForwardedAt());
    verify(accountInviteRepository, times(2)).save(invite);
    // exactly one link was minted - a failed send must never cost a second slot
    verify(publicDpaForwardClient, times(1)).createForwardSignLink(any(), any());
  }

  @Test
  void forwardDpa_flagsTheMailAsSent_When_deliverySucceeds() {
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(publicDpaForwardClient.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .thenReturn(signInvite());

    var result = service.forwardDpa(RAW_TOKEN, "legal@example.org");

    assertTrue(result.mailSent());
    verify(publicDpaForwardClient, times(1)).createForwardSignLink(any(), any());
  }

  /** No recipient means nothing was ever meant to be delivered, so the flag stays clear. */
  @Test
  void forwardDpa_reportsNoMail_When_noRecipientIsGiven() {
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(publicDpaForwardClient.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .thenReturn(signInvite());

    assertFalse(service.forwardDpa(RAW_TOKEN, null).mailSent());
  }

  /**
   * The degradation is scoped to the mail alone. A throttled mint produced no link at all, so there
   * is nothing to hand back and the throttle must still reach the caller as 429.
   */
  @Test
  void forwardDpa_stillFails_When_theMintIsThrottled() {
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(publicDpaForwardClient.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .thenThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "throttled"));

    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.forwardDpa(RAW_TOKEN, "legal@example.org"));
    assertNull(invite.getDpaForwardedAt());
    verify(dpaForwardEmailService, never()).sendSigningLink(any());
  }

  @Test
  void forwardDpa_refusesOnceTheInviteSpentItsForwardBudget() {
    // the route is anonymous and each call mails a LIVE signing link to a caller-supplied address,
    // so an unbounded endpoint is a mail relay for someone else's tenant
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    invite.setDpaForwardCount(TenantAdminOnboardingService.MAX_DPA_FORWARDS_PER_INVITE);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));

    assertThrows(
        BadRequestException.class, () -> service.forwardDpa(RAW_TOKEN, "attacker@example.org"));
    // no link minted, and above all no mail sent
    verify(publicDpaForwardClient, never()).createForwardSignLink(any(), any());
    verify(dpaForwardEmailService, never()).sendSigningLink(any());
    // a refused attempt must not cost anything either: the count is untouched and nothing is
    // written, so a flood cannot push an already-exhausted invite further out of reach
    assertEquals(
        TenantAdminOnboardingService.MAX_DPA_FORWARDS_PER_INVITE, invite.getDpaForwardCount());
    verify(accountInviteRepository, never()).save(any());
  }

  @Test
  void forwardDpa_refundsTheAttempt_When_theLinkCouldNotBeMinted() {
    // an upstream failure must not burn one of the three forwards a Träger gets - otherwise a
    // TenantService outage silently exhausts a legitimate invitation. Since the #1065 review the
    // attempt is RESERVED before the mint (so the row lock is never held across the remote call)
    // and REFUNDED when the mint produced no link - the net budget stays untouched.
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    invite.setDpaForwardCount(1);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(publicDpaForwardClient.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .thenThrow(new InternalServerErrorException("TenantService unavailable"));

    assertThrows(
        InternalServerErrorException.class,
        () -> service.forwardDpa(RAW_TOKEN, "legal@example.org"));

    assertEquals(1, invite.getDpaForwardCount());
    // reserve + refund are each persisted in their own short transaction
    verify(accountInviteRepository, times(2)).save(invite);
    assertNull(invite.getDpaForwardedAt());
    verify(dpaForwardEmailService, never()).sendSigningLink(any());
  }

  /**
   * The provider broke its contract (see parseExpiry): a missing or unparseable expiry must surface
   * as 500 instead of being swallowed as a "mail failure" while the call answers 200 with an expiry
   * string no mail could state (CodeRabbit, #1065).
   */
  @Test
  void forwardDpa_failsLoudly_When_theProviderReturnsNoExpiry() {
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(publicDpaForwardClient.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .thenReturn(signInvite().expiresAt(null));

    assertThrows(
        InternalServerErrorException.class,
        () -> service.forwardDpa(RAW_TOKEN, "legal@example.org"));

    // the broken contract is not a mail-delivery problem, so no send may have been attempted
    verify(dpaForwardEmailService, never()).sendSigningLink(any());
  }

  @Test
  void forwardDpa_failsLoudly_When_theProviderReturnsAnUnparseableExpiry() {
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(publicDpaForwardClient.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .thenReturn(signInvite().expiresAt("29.08.2026 14:31"));

    assertThrows(
        InternalServerErrorException.class,
        () -> service.forwardDpa(RAW_TOKEN, "legal@example.org"));

    verify(dpaForwardEmailService, never()).sendSigningLink(any());
  }

  /** A well-formed expiry still passes through verbatim when no mail states a validity window. */
  @Test
  void forwardDpa_returnsTheRawExpiry_When_noMailNeedsToStateIt() {
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(publicDpaForwardClient.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .thenReturn(signInvite());

    assertEquals("2026-08-29T14:31:07", service.forwardDpa(RAW_TOKEN, null).expiresAt());
  }

  /**
   * The provider contract holds on the recipient-less path too: without this the broken value was
   * simply handed back with a 200, because parseExpiry only ran when a mail had to state it
   * (CodeRabbit, #1065).
   */
  @Test
  void forwardDpa_failsLoudly_When_theProviderExpiryIsBroken_andNoRecipientIsGiven() {
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(publicDpaForwardClient.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .thenReturn(signInvite().expiresAt(null));

    assertThrows(InternalServerErrorException.class, () -> service.forwardDpa(RAW_TOKEN, null));

    verify(dpaForwardEmailService, never()).sendSigningLink(any());
  }

  /**
   * A broken provider answer must not leave a completed forward behind: the proof write happens
   * only after the expiry parsed, so dpaForwardedAt stays null (CodeRabbit, #1065).
   */
  @Test
  void forwardDpa_doesNotRecordTheForward_When_theProviderExpiryIsBroken() {
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(publicDpaForwardClient.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .thenReturn(signInvite().expiresAt("29.08.2026 14:31"));

    assertThrows(
        InternalServerErrorException.class,
        () -> service.forwardDpa(RAW_TOKEN, "legal@example.org"));

    assertNull(invite.getDpaForwardedAt());
  }

  @Test
  void forwardDpa_countsEachForwardAgainstTheBudget() {
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    invite.setDpaForwardCount(1);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(publicDpaForwardClient.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .thenReturn(signInvite());

    service.forwardDpa(RAW_TOKEN, "legal@example.org");

    assertEquals(2, invite.getDpaForwardCount());
    verify(accountInviteRepository, times(2)).save(invite);
  }

  @Test
  void forwardDpa_rejectsAConsumedInvite() {
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH))
        .thenReturn(Optional.of(tenantAdminInvite(AccountInviteStatus.ACCEPTED)));

    assertThrows(
        AccountInviteLinkException.class, () -> service.forwardDpa(RAW_TOKEN, "legal@example.org"));
    verify(publicDpaForwardClient, never()).createForwardSignLink(any(), any());
  }

  @Test
  void forwardDpa_rejectsAnExpiredInvite_andPreservesTheExpiredState() {
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    invite.setExpiresAt(LocalDateTime.now().minusDays(1));
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));

    assertThrows(AccountInviteLinkException.class, () -> service.forwardDpa(RAW_TOKEN, null));
    verify(publicDpaForwardClient, never()).createForwardSignLink(any(), any());
    // the persistence half is the whole reason forwardDpa carries noRollbackFor: without it the
    // link-death exception rolls the EXPIRED transition back and the next call re-offers the
    // forward. Asserting only the throw leaves that annotation untested.
    assertEquals(AccountInviteStatus.EXPIRED, invite.getStatus());
    verify(accountInviteRepository).save(invite);
  }

  @Test
  void forwardDpa_doesNotRecordTheForward_When_theLinkCreationFails() {
    // given TenantService rejects the reservation pair
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(publicDpaForwardClient.createForwardSignLink(RESERVED_TENANT_ID, RESERVATION_TOKEN))
        .thenThrow(new AccountInviteLinkException(AccountInviteLinkException.Reason.CONSUMED));

    // when / then: no forward is claimed, so registration still requires an acceptance
    assertThrows(AccountInviteLinkException.class, () -> service.forwardDpa(RAW_TOKEN, null));
    assertNull(invite.getDpaForwardedAt());
    verify(dpaForwardEmailService, never()).sendSigningLink(any());
  }

  /**
   * The controller test can only pin that no role dispatch happens; the rejection itself lives
   * here. Without this case nothing proved that a counsellor token is refused — the controller stub
   * answered for any token (CodeRabbit, #1065).
   */
  @Test
  void forwardDpa_rejectsANonTenantAdminToken() {
    var counsellorInvite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    counsellorInvite.setTargetRole(AccountInviteTargetRole.COUNSELLOR);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH))
        .thenReturn(Optional.of(counsellorInvite));

    assertThrows(NotFoundException.class, () -> service.forwardDpa(RAW_TOKEN, "legal@example.org"));

    // no link may be minted and no attempt may be spent for a token that does not belong here
    verifyNoInteractions(publicDpaForwardClient);
    verify(accountInviteRepository, never()).save(any());
  }

  @Test
  void forwardDpa_rejectsAnInviteWithoutAReservation() {
    var invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    invite.setTenantIdReservationToken(null);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));

    assertThrows(InternalServerErrorException.class, () -> service.forwardDpa(RAW_TOKEN, null));
  }

  @Test
  void registerTenantAdmin_shortPassword_throwsBadRequest() {
    var command =
        new RegisterTenantAdminCommand(
            "Beispiel gGmbH",
            "beispiel",
            null,
            true,
            null,
            null,
            null,
            null,
            "short",
            RESERVED_TENANT_ID,
            RESERVATION_TOKEN);

    assertThrows(BadRequestException.class, () -> service.registerTenantAdmin(RAW_TOKEN, command));
  }

  @Test
  void registerTenantAdmin_reservationMismatch_throwsNotFound() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    var command =
        new RegisterTenantAdminCommand(
            "Beispiel gGmbH",
            "beispiel",
            null,
            true,
            null,
            null,
            null,
            null,
            "s3cretPassword",
            RESERVED_TENANT_ID,
            "some-other-token");

    assertThrows(NotFoundException.class, () -> service.registerTenantAdmin(RAW_TOKEN, command));
    verify(accountInviteRepository, never()).claimForAcceptance(anyLong(), any(), any());
  }

  @Test
  void registerTenantAdmin_missingReservation_throwsInternalServerError() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    invite.setTenantIdReservationToken(null);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));

    assertThrows(
        InternalServerErrorException.class,
        () -> service.registerTenantAdmin(RAW_TOKEN, validCommand()));
  }

  @Test
  void registerTenantAdmin_alreadyConsumedInvite_throwsConsumed() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.ACCEPTED);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));

    var exception =
        assertThrows(
            AccountInviteLinkException.class,
            () -> service.registerTenantAdmin(RAW_TOKEN, validCommand()));

    assertEquals(AccountInviteLinkException.Reason.CONSUMED, exception.getReason());
    verify(createAdminService, never()).createNewTenantAdmin(any());
  }

  @Test
  void registerTenantAdmin_lostClaimRace_reportsWinnersState() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    givenPublishedOperatorDpa();
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(accountInviteRepository.claimForAcceptance(eq(7L), isNull(), any())).thenReturn(0);
    AccountInvite winner = tenantAdminInvite(AccountInviteStatus.ACCEPTED);
    when(accountInviteRepository.findById(7L)).thenReturn(Optional.of(winner));

    var exception =
        assertThrows(
            AccountInviteLinkException.class,
            () -> service.registerTenantAdmin(RAW_TOKEN, validCommand()));

    assertEquals(AccountInviteLinkException.Reason.CONSUMED, exception.getReason());
    verify(createAdminService, never()).createNewTenantAdmin(any());
  }

  @Test
  void registerTenantAdmin_tenantCreationFails_rollsBackKeycloakUserAndRethrows() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    givenPublishedOperatorDpa();
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(accountInviteRepository.claimForAcceptance(eq(7L), isNull(), any())).thenReturn(1);
    when(accountInviteRepository.findById(7L)).thenReturn(Optional.of(invite));
    Admin admin =
        Admin.builder()
            .id("kc-user-1")
            .username("tenant.admin@example.org")
            .email("tenant.admin@example.org")
            .firstName("Erika")
            .lastName("Beispiel")
            .build();
    when(createAdminService.createNewTenantAdmin(any())).thenReturn(admin);
    when(identitySecondFactor.getOtpCredential(anyString()))
        .thenReturn(new IdentityOtpCredential(null, "TOTPSECRET", null, null));
    when(tenantCreationClient.createTenant(any()))
        .thenThrow(new ConflictException("reservation no longer consumable"));

    assertThrows(
        ConflictException.class, () -> service.registerTenantAdmin(RAW_TOKEN, validCommand()));

    verify(identityAccountRemover).rollbackUser("kc-user-1");
  }

  @Test
  void registerTenantAdmin_missingTotpMaterial_rollsBackKeycloakUser() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    givenPublishedOperatorDpa();
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(accountInviteRepository.claimForAcceptance(eq(7L), isNull(), any())).thenReturn(1);
    Admin admin =
        Admin.builder()
            .id("kc-user-1")
            .username("tenant.admin@example.org")
            .email("tenant.admin@example.org")
            .firstName("Erika")
            .lastName("Beispiel")
            .build();
    when(createAdminService.createNewTenantAdmin(any())).thenReturn(admin);
    when(identitySecondFactor.getOtpCredential(anyString()))
        .thenReturn(IdentityOtpCredential.empty());

    assertThrows(
        InternalServerErrorException.class,
        () -> service.registerTenantAdmin(RAW_TOKEN, validCommand()));

    verify(identityAccountRemover).rollbackUser("kc-user-1");
    verify(tenantCreationClient, never()).createTenant(any());
  }

  // --- two-factor ---

  @Test
  void activateTwoFactor_happyPath_activatesGateAndClearsSecret() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.ACCEPTED);
    invite.setAcceptedByUserId("kc-user-1");
    invite.setTotpPendingSecret("TOTPSECRET");
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(identityProfileLookup.findById("kc-user-1"))
        .thenReturn(
            Optional.of(
                new IdentityProfile("kc-user-1", "enc.keycloak-username", null, null, null)));
    when(identitySecondFactor.setUpOtpCredential("enc.keycloak-username", "123456", "TOTPSECRET"))
        .thenReturn(true);

    service.activateTwoFactor(RAW_TOKEN, "123456");

    verify(accountInviteService).markTwoFactorActive("kc-user-1");
    assertNull(invite.getTotpPendingSecret());
    verify(accountInviteRepository).save(invite);
  }

  @Test
  void activateTwoFactor_invalidCode_throwsBadRequestWithoutActivation() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.ACCEPTED);
    invite.setAcceptedByUserId("kc-user-1");
    invite.setTotpPendingSecret("TOTPSECRET");
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    when(identityProfileLookup.findById("kc-user-1"))
        .thenReturn(
            Optional.of(
                new IdentityProfile("kc-user-1", "enc.keycloak-username", null, null, null)));
    when(identitySecondFactor.setUpOtpCredential(anyString(), anyString(), anyString()))
        .thenReturn(false);

    assertThrows(BadRequestException.class, () -> service.activateTwoFactor(RAW_TOKEN, "000000"));

    verify(accountInviteService, never()).markTwoFactorActive(anyString());
    assertEquals("TOTPSECRET", invite.getTotpPendingSecret());
  }

  @Test
  void activateTwoFactor_blankOtp_throwsBadRequest() {
    assertThrows(BadRequestException.class, () -> service.activateTwoFactor(RAW_TOKEN, " "));
  }

  @Test
  void activateTwoFactor_registrationNotDoneYet_throwsBadRequest() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.EMAIL_SENT);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));

    assertThrows(BadRequestException.class, () -> service.activateTwoFactor(RAW_TOKEN, "123456"));
  }

  @Test
  void activateTwoFactor_gateAlreadySatisfied_throwsConsumed() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.ACCEPTED);
    invite.setTwoFactorStatus(TwoFactorGateStatus.ACTIVE);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));

    var exception =
        assertThrows(
            AccountInviteLinkException.class, () -> service.activateTwoFactor(RAW_TOKEN, "123456"));

    assertEquals(AccountInviteLinkException.Reason.CONSUMED, exception.getReason());
  }

  @Test
  void activateTwoFactor_expiredResumeWindow_throwsConsumed() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.ACCEPTED);
    invite.setExpiresAt(LocalDateTime.now().minusMinutes(5));
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));

    var exception =
        assertThrows(
            AccountInviteLinkException.class, () -> service.activateTwoFactor(RAW_TOKEN, "123456"));

    assertEquals(AccountInviteLinkException.Reason.CONSUMED, exception.getReason());
  }

  @Test
  void activateTwoFactor_noPendingSecret_throwsBadRequest() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.ACCEPTED);
    invite.setAcceptedByUserId("kc-user-1");
    invite.setTotpPendingSecret(null);
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));

    assertThrows(BadRequestException.class, () -> service.activateTwoFactor(RAW_TOKEN, "123456"));
  }
}
