package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService.CounsellorOnboardingState;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService.CounsellorRegistrationResult;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService.RegisterCounsellorCommand;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService.TopicOption;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantAdminOnboardingService;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantAdminOnboardingService.OnboardingInviteState;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantAdminOnboardingService.RegisterTenantAdminCommand;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantAdminOnboardingService.TenantAdminRegistrationResult;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

@ExtendWith(MockitoExtension.class)
class TenantAdminOnboardingControllerTest {

  private static final String OPERATOR_DPA_JSON =
      "{\"de\":\"<h2>Auftragsverarbeitung</h2>\",\"en\":\"<h2>Data processing</h2>\"}";

  @Mock private TenantAdminOnboardingService onboardingService;
  @Mock private CounsellorOnboardingService counsellorOnboardingService;
  @Mock private AccountInviteService accountInviteService;

  private TenantAdminOnboardingController controller;

  @BeforeEach
  void setUp() {
    controller =
        new TenantAdminOnboardingController(
            onboardingService, counsellorOnboardingService, accountInviteService);
    // Role probe of the shared endpoints (#997): the pre-existing tests exercise the
    // tenant-admin dispatch; counsellor tests re-stub the probe with the COUNSELLOR role. The
    // probe reads the role alone and takes no row lock (#1008 review).
    org.mockito.Mockito.lenient()
        .when(accountInviteService.findTargetRoleByToken("tok"))
        .thenReturn(AccountInviteTargetRole.TENANT_ADMIN);
  }

  private static AccountInvite invite() {
    return AccountInvite.builder()
        .id(7L)
        .targetRole(AccountInviteTargetRole.TENANT_ADMIN)
        .tenantId(21L)
        .tenantIdReservationToken("reservation-token")
        .recipientEmail("tenant.admin@example.org")
        .firstName("Erika")
        .lastName("Beispiel")
        .status(AccountInviteStatus.EMAIL_SENT)
        .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
        .expiresAt(LocalDateTime.of(2026, 8, 30, 12, 0))
        .build();
  }

  private static AccountInvite counsellorInvite() {
    return AccountInvite.builder()
        .id(8L)
        .targetRole(AccountInviteTargetRole.COUNSELLOR)
        .tenantId(21L)
        .agencyId(5L)
        .departmentId(12L)
        .recipientEmail("counsellor@example.org")
        .firstName("Lena")
        .lastName("Beraterin")
        .status(AccountInviteStatus.EMAIL_SENT)
        .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
        .expiresAt(LocalDateTime.of(2026, 8, 30, 12, 0))
        .build();
  }

  private void probeAnswersCounsellor() {
    when(accountInviteService.findTargetRoleByToken("tok"))
        .thenReturn(AccountInviteTargetRole.COUNSELLOR);
  }

  @Test
  void resolveOnboardingInvite_plainState_mapsInviteFieldsWithoutPhase() {
    when(onboardingService.resolveOnboardingInvite("tok"))
        .thenReturn(new OnboardingInviteState(invite(), false, OPERATOR_DPA_JSON));

    var response = controller.resolveOnboardingInvite("tok");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    var body = response.getBody();
    assertNotNull(body);
    assertEquals("tenant.admin@example.org", body.recipientEmail);
    assertEquals("Erika", body.firstName);
    assertEquals("Beispiel", body.lastName);
    assertEquals(21L, body.reservedTenantId);
    assertEquals("reservation-token", body.tenantIdReservationToken);
    assertEquals(LocalDateTime.of(2026, 8, 30, 12, 0), body.expiresAt);
    // The DPA step must render the operator's contract text (and its anchor/TOC navigation),
    // never a placeholder while the invitee ticks the acceptance box.
    assertEquals(OPERATOR_DPA_JSON, body.dpaContent);
    assertNull(body.phase);
    assertNull(body.twoFactor);
  }

  @Test
  void resolveOnboardingInvite_withoutPublishedOperatorDpa_answersWithNullDpaContent() {
    when(onboardingService.resolveOnboardingInvite("tok"))
        .thenReturn(new OnboardingInviteState(invite(), false, null));

    var body = controller.resolveOnboardingInvite("tok").getBody();

    assertNotNull(body);
    assertNull(body.dpaContent);
  }

  @Test
  void resolveOnboardingInvite_resumableState_mapsPhaseAndStoredSecret() {
    AccountInvite resumable = invite();
    resumable.setStatus(AccountInviteStatus.ACCEPTED);
    resumable.setTotpPendingSecret("STOREDSECRET");
    when(onboardingService.resolveOnboardingInvite("tok"))
        .thenReturn(new OnboardingInviteState(resumable, true, null));

    var body = controller.resolveOnboardingInvite("tok").getBody();

    assertNotNull(body);
    assertEquals("PENDING_2FA_ACTIVATION", body.phase);
    assertNotNull(body.twoFactor);
    assertEquals("STOREDSECRET", body.twoFactor.secret);
    assertNull(body.twoFactor.qrCodeBase64);
  }

  @Test
  void resolveOnboardingInvite_resumableWithoutStoredSecret_omitsTwoFactorMaterial() {
    AccountInvite resumable = invite();
    resumable.setStatus(AccountInviteStatus.ACCEPTED);
    when(onboardingService.resolveOnboardingInvite("tok"))
        .thenReturn(new OnboardingInviteState(resumable, true, null));

    var body = controller.resolveOnboardingInvite("tok").getBody();

    assertNotNull(body);
    assertEquals("PENDING_2FA_ACTIVATION", body.phase);
    assertNull(body.twoFactor);
  }

  @Test
  void registerTenantAdmin_mapsRequestToCommandAndResultToResponse() {
    when(onboardingService.registerTenantAdmin(eq("tok"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new TenantAdminRegistrationResult(21L, "TOTPSECRET", "QRBASE64"));

    var request = new TenantAdminOnboardingController.TenantAdminRegistrationRequestDTO();
    request.organisation = new TenantAdminOnboardingController.OrganisationDataDTO();
    request.organisation.name = "Beispiel gGmbH";
    request.organisation.subdomain = "beispiel";
    request.organisation.address = "Musterstrasse 1";
    request.dpa = new TenantAdminOnboardingController.DpaAcceptanceDataDTO();
    request.dpa.accepted = true;
    request.dpa.signerName = "Erika Beispiel";
    request.account = new TenantAdminOnboardingController.AccountDataDTO();
    request.account.password = "s3cretPassword";
    request.reservedTenantId = 21L;
    request.tenantIdReservationToken = "reservation-token";

    var response = controller.registerTenantAdmin("tok", request);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    var body = response.getBody();
    assertNotNull(body);
    assertEquals(21L, body.tenantId);
    assertNotNull(body.twoFactor);
    assertEquals("TOTPSECRET", body.twoFactor.secret);
    assertEquals("QRBASE64", body.twoFactor.qrCodeBase64);

    ArgumentCaptor<RegisterTenantAdminCommand> captor =
        ArgumentCaptor.forClass(RegisterTenantAdminCommand.class);
    verify(onboardingService).registerTenantAdmin(eq("tok"), captor.capture());
    RegisterTenantAdminCommand command = captor.getValue();
    assertEquals("Beispiel gGmbH", command.organisationName());
    assertEquals("beispiel", command.subdomain());
    assertEquals("Musterstrasse 1", command.address());
    assertTrue(command.dpaAccepted());
    assertEquals("Erika Beispiel", command.dpaSignerName());
    assertEquals("s3cretPassword", command.password());
    assertEquals(21L, command.reservedTenantId());
    assertEquals("reservation-token", command.tenantIdReservationToken());
  }

  @Test
  void registerTenantAdmin_nullBody_mapsToEmptyCommandInsteadOfNpe() {
    when(onboardingService.registerTenantAdmin(eq("tok"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new TenantAdminRegistrationResult(21L, "TOTPSECRET", null));

    controller.registerTenantAdmin("tok", null);

    ArgumentCaptor<RegisterTenantAdminCommand> captor =
        ArgumentCaptor.forClass(RegisterTenantAdminCommand.class);
    verify(onboardingService).registerTenantAdmin(eq("tok"), captor.capture());
    assertNull(captor.getValue().organisationName());
    assertNull(captor.getValue().password());
  }

  @Test
  void activateTwoFactor_delegatesOtp() {
    var request = new TenantAdminOnboardingController.TwoFactorActivationRequestDTO();
    request.otp = "123456";

    var response = controller.activateTwoFactor("tok", request);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(onboardingService).activateTwoFactor("tok", "123456");
  }

  @Test
  void activateTwoFactor_nullBody_delegatesNullOtp() {
    controller.activateTwoFactor("tok", null);

    verify(onboardingService).activateTwoFactor("tok", null);
  }

  @Test
  void resolveOnboardingInvite_counsellorInvite_dispatchesToCounsellorFlowWithCoverage() {
    probeAnswersCounsellor();
    when(counsellorOnboardingService.resolveOnboardingInvite("tok"))
        .thenReturn(
            new CounsellorOnboardingState(
                counsellorInvite(),
                false,
                java.util.List.of(new TopicOption(12L, "Family counselling"))));

    var body = controller.resolveOnboardingInvite("tok").getBody();

    assertNotNull(body);
    assertEquals("COUNSELLOR", body.targetRole);
    assertEquals("counsellor@example.org", body.recipientEmail);
    assertEquals("Lena", body.firstName);
    assertEquals(21L, body.tenantId);
    assertEquals(5L, body.agencyId);
    assertEquals(12L, body.departmentId);
    assertNotNull(body.topics);
    assertEquals(1, body.topics.size());
    assertEquals(12L, body.topics.get(0).id);
    assertEquals("Family counselling", body.topics.get(0).name);
    assertNull(body.phase);
    assertNull(body.reservedTenantId);
  }

  @Test
  void resolveOnboardingInvite_counsellorResume_mapsPhaseAndStoredSecret() {
    probeAnswersCounsellor();
    AccountInvite resumable = counsellorInvite();
    resumable.setStatus(AccountInviteStatus.ACCEPTED);
    resumable.setTotpPendingSecret("STOREDSECRET");
    when(counsellorOnboardingService.resolveOnboardingInvite("tok"))
        .thenReturn(new CounsellorOnboardingState(resumable, true, java.util.List.of()));

    var body = controller.resolveOnboardingInvite("tok").getBody();

    assertNotNull(body);
    assertEquals("PENDING_2FA_ACTIVATION", body.phase);
    assertNotNull(body.twoFactor);
    assertEquals("STOREDSECRET", body.twoFactor.secret);
  }

  @Test
  void registerTenantAdmin_counsellorInvite_dispatchesWizardFieldsToCounsellorFlow() {
    probeAnswersCounsellor();
    when(counsellorOnboardingService.registerCounsellor(
            eq("tok"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new CounsellorRegistrationResult("consultant-1", "TOTPSECRET", "QR", true));

    var request = new TenantAdminOnboardingController.TenantAdminRegistrationRequestDTO();
    request.account = new TenantAdminOnboardingController.AccountDataDTO();
    request.account.username = "lena.b";
    request.account.password = "s3cretPassword";
    request.person = new TenantAdminOnboardingController.PersonDataDTO();
    request.person.salutation = "counsellor_female";
    request.person.position = "Head of counselling centre";
    request.person.title = "Dipl.-Soz.Päd.";
    request.names = new TenantAdminOnboardingController.DisplayNamesDataDTO();
    request.names.publicName = "Lena";
    request.names.internalDisplayName = "Lena B. (Nord)";
    request.topicIds = java.util.List.of(12L);

    var response = controller.registerTenantAdmin("tok", request);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    var body = response.getBody();
    assertNotNull(body);
    assertEquals("consultant-1", body.consultantId);
    assertEquals("PENDING_2FA_ACTIVATION", body.phase);
    assertNull(body.tenantId);
    assertNotNull(body.twoFactor);
    assertEquals("TOTPSECRET", body.twoFactor.secret);

    ArgumentCaptor<RegisterCounsellorCommand> captor =
        ArgumentCaptor.forClass(RegisterCounsellorCommand.class);
    verify(counsellorOnboardingService).registerCounsellor(eq("tok"), captor.capture());
    RegisterCounsellorCommand command = captor.getValue();
    assertEquals("lena.b", command.username());
    assertEquals("s3cretPassword", command.password());
    assertEquals("counsellor_female", command.salutation());
    assertEquals("Head of counselling centre", command.position());
    assertEquals("Dipl.-Soz.Päd.", command.title());
    assertEquals("Lena", command.displayName());
    assertEquals("Lena B. (Nord)", command.internalDisplayName());
    assertEquals(java.util.List.of(12L), command.topicIds());
  }

  @Test
  void registerTenantAdmin_counsellorWithWaivedGate_answersCompletedWithoutTwoFactor() {
    probeAnswersCounsellor();
    when(counsellorOnboardingService.registerCounsellor(
            eq("tok"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new CounsellorRegistrationResult("consultant-1", null, null, false));

    var body = controller.registerTenantAdmin("tok", null).getBody();

    assertNotNull(body);
    assertEquals("COMPLETED", body.phase);
    assertNull(body.twoFactor);
  }

  @Test
  void activateTwoFactor_counsellorInvite_dispatchesToCounsellorFlow() {
    probeAnswersCounsellor();
    var request = new TenantAdminOnboardingController.TwoFactorActivationRequestDTO();
    request.otp = "654321";

    var response = controller.activateTwoFactor("tok", request);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(counsellorOnboardingService).activateTwoFactor("tok", "654321");
    org.mockito.Mockito.verifyNoInteractions(onboardingService);
  }

  @Test
  void publicOnboardingEndpoints_haveNoPreAuthorizeAndMapBothPrefixVariants() throws Exception {
    Method resolve =
        TenantAdminOnboardingController.class.getMethod("resolveOnboardingInvite", String.class);
    Method register =
        TenantAdminOnboardingController.class.getMethod(
            "registerTenantAdmin",
            String.class,
            TenantAdminOnboardingController.TenantAdminRegistrationRequestDTO.class);
    Method twoFactor =
        TenantAdminOnboardingController.class.getMethod(
            "activateTwoFactor",
            String.class,
            TenantAdminOnboardingController.TwoFactorActivationRequestDTO.class);

    assertNull(resolve.getAnnotation(PreAuthorize.class));
    assertNull(register.getAnnotation(PreAuthorize.class));
    assertNull(twoFactor.getAnnotation(PreAuthorize.class));

    assertTrue(
        Arrays.asList(resolve.getAnnotation(GetMapping.class).value())
            .containsAll(
                Arrays.asList(
                    "/users/account-invites/{token}/onboarding",
                    "/service/users/account-invites/{token}/onboarding")));
    assertTrue(
        Arrays.asList(register.getAnnotation(PostMapping.class).value())
            .containsAll(
                Arrays.asList(
                    "/users/account-invites/{token}/onboarding/register",
                    "/service/users/account-invites/{token}/onboarding/register")));
    assertTrue(
        Arrays.asList(twoFactor.getAnnotation(PostMapping.class).value())
            .containsAll(
                Arrays.asList(
                    "/users/account-invites/{token}/onboarding/two-factor",
                    "/service/users/account-invites/{token}/onboarding/two-factor")));
  }

  // --- DPA forward from the wizard (ORISO-Admin#722) ---

  @Test
  void forwardDpa_passesTheRecipientAndReturnsTheSignLink() {
    when(onboardingService.forwardDpa("raw-token", "legal@example.org"))
        .thenReturn(
            new TenantAdminOnboardingService.DpaForwardResult(
                "https://app.oriso.org/dpa-sign/RAWSIGNTOKEN", "2026-08-29T14:31:07", true));
    var request = new TenantAdminOnboardingController.DpaForwardRequestDTO();
    request.recipientEmail = "legal@example.org";

    var response = controller.forwardDpa("raw-token", request);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("https://app.oriso.org/dpa-sign/RAWSIGNTOKEN", response.getBody().signUrl);
    assertEquals("2026-08-29T14:31:07", response.getBody().expiresAt);
    assertTrue(response.getBody().mailSent);
  }

  /** Option A: an undelivered mail still answers 200 with the link, flagged as not sent. */
  @Test
  void forwardDpa_returnsTheLinkFlaggedUnsent_When_theMailCouldNotBeDelivered() {
    when(onboardingService.forwardDpa("raw-token", "legal@example.org"))
        .thenReturn(
            new TenantAdminOnboardingService.DpaForwardResult(
                "https://app.oriso.org/dpa-sign/RAWSIGNTOKEN", "2026-08-29T14:31:07", false));
    var request = new TenantAdminOnboardingController.DpaForwardRequestDTO();
    request.recipientEmail = "legal@example.org";

    var response = controller.forwardDpa("raw-token", request);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("https://app.oriso.org/dpa-sign/RAWSIGNTOKEN", response.getBody().signUrl);
    assertFalse(response.getBody().mailSent);
  }

  @Test
  void forwardDpa_toleratesAnAbsentBody() {
    when(onboardingService.forwardDpa(eq("raw-token"), eq(null)))
        .thenReturn(
            new TenantAdminOnboardingService.DpaForwardResult(
                "https://app.oriso.org/dpa-sign/RAWSIGNTOKEN", "2026-08-29T14:31:07", false));

    var response = controller.forwardDpa("raw-token", null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(onboardingService).forwardDpa("raw-token", null);
  }

  @Test
  void forwardDpa_isTenantAdminOnly_andDoesNotDispatchByRole() {
    // forwardDpa is the only public onboarding endpoint here that does NOT branch on the token's
    // role: forwarding a DPA is meaningless for a counsellor invite, which has no tenant to sign
    // for. This pins that difference so a refactor cannot quietly route counsellor tokens into
    // tenant-admin forwarding; the service-level lookup stays the enforcement point (it answers
    // 404 for a non-tenant-admin token).
    when(onboardingService.forwardDpa("raw-token", null))
        .thenReturn(
            new TenantAdminOnboardingService.DpaForwardResult(
                "https://app.oriso.org/dpa-sign/RAWSIGNTOKEN", "2026-08-29T14:31:07"));

    controller.forwardDpa("raw-token", null);

    verify(onboardingService).forwardDpa("raw-token", null);
    // the role probe the other three endpoints run is deliberately absent here — there is no
    // counsellor counterpart to dispatch to (CounsellorOnboardingService has no forwardDpa at
    // all), so the role check would only add a second lookup with no branch to take
    verify(accountInviteService, never()).findTargetRoleByToken(any());
  }

  @Test
  void forwardDpa_isAnonymousAndMappedOnBothPrefixes() throws Exception {
    Method forwardDpa =
        TenantAdminOnboardingController.class.getMethod(
            "forwardDpa", String.class, TenantAdminOnboardingController.DpaForwardRequestDTO.class);

    assertNull(forwardDpa.getAnnotation(PreAuthorize.class));
    assertTrue(
        Arrays.asList(forwardDpa.getAnnotation(PostMapping.class).value())
            .containsAll(
                Arrays.asList(
                    "/users/account-invites/{token}/onboarding/dpa-forward",
                    "/service/users/account-invites/{token}/onboarding/dpa-forward")));
  }
}
