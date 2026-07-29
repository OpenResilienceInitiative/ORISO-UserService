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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.OtpInfoDTO;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
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
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
  @Mock private TenantCreationClient tenantCreationClient;
  @Mock private OperatorDpaContentClient operatorDpaContentClient;

  private TenantAdminOnboardingService service;

  @BeforeEach
  void setUp() {
    service =
        new TenantAdminOnboardingService(
            accountInviteRepository,
            accountInviteService,
            createAdminService,
            identityClient,
            tenantCreationClient,
            operatorDpaContentClient,
            new UsernameTranscoder());
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
    when(identityClient.getOtpCredential(anyString()))
        .thenReturn(new OtpInfoDTO().otpSecret("TOTPSECRET").otpSecretQrCode("QRBASE64"));
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
    when(identityClient.getOtpCredential(anyString()))
        .thenReturn(new OtpInfoDTO().otpSecret("TOTPSECRET"));
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
    when(identityClient.getOtpCredential(anyString()))
        .thenReturn(new OtpInfoDTO().otpSecret("TOTPSECRET"));
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

  @Test
  void registerTenantAdmin_dpaNotAccepted_throwsBadRequest() {
    var command =
        new RegisterTenantAdminCommand(
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

    assertThrows(BadRequestException.class, () -> service.registerTenantAdmin(RAW_TOKEN, command));
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
    when(identityClient.getOtpCredential(anyString()))
        .thenReturn(new OtpInfoDTO().otpSecret("TOTPSECRET"));
    when(tenantCreationClient.createTenant(any()))
        .thenThrow(new ConflictException("reservation no longer consumable"));

    assertThrows(
        ConflictException.class, () -> service.registerTenantAdmin(RAW_TOKEN, validCommand()));

    verify(identityClient).rollBackUser("kc-user-1");
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
    when(identityClient.getOtpCredential(anyString())).thenReturn(new OtpInfoDTO());

    assertThrows(
        InternalServerErrorException.class,
        () -> service.registerTenantAdmin(RAW_TOKEN, validCommand()));

    verify(identityClient).rollBackUser("kc-user-1");
    verify(tenantCreationClient, never()).createTenant(any());
  }

  // --- two-factor ---

  @Test
  void activateTwoFactor_happyPath_activatesGateAndClearsSecret() {
    AccountInvite invite = tenantAdminInvite(AccountInviteStatus.ACCEPTED);
    invite.setAcceptedByUserId("kc-user-1");
    invite.setTotpPendingSecret("TOTPSECRET");
    when(accountInviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
    UserRepresentation keycloakUser = new UserRepresentation();
    keycloakUser.setUsername("enc.keycloak-username");
    when(identityClient.getById("kc-user-1")).thenReturn(keycloakUser);
    when(identityClient.setUpOtpCredential("enc.keycloak-username", "123456", "TOTPSECRET"))
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
    UserRepresentation keycloakUser = new UserRepresentation();
    keycloakUser.setUsername("enc.keycloak-username");
    when(identityClient.getById("kc-user-1")).thenReturn(keycloakUser);
    when(identityClient.setUpOtpCredential(anyString(), anyString(), anyString()))
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
