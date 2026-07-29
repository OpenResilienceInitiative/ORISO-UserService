package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
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

  private TenantAdminOnboardingController controller;

  @BeforeEach
  void setUp() {
    controller = new TenantAdminOnboardingController(onboardingService);
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
}
