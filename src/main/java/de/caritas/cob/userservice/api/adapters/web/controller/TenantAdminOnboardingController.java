package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantAdminOnboardingService;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantAdminOnboardingService.OnboardingInviteState;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantAdminOnboardingService.RegisterTenantAdminCommand;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantAdminOnboardingService.TenantAdminRegistrationResult;
import java.time.LocalDateTime;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * PUBLIC tenant-admin onboarding endpoints (#569 chain fix; UserService side of the Admin panel's
 * TEN-INV-U8 flow). All three endpoints are anonymous by design — the invitee has no account yet;
 * the raw invite token in the path is the only credential. Mapped with and without the {@code
 * /service} prefix because the API gateway forwards the prefix unchanged (same convention as the
 * other public endpoints). Response/request shapes are pinned to the Admin panel client ({@code
 * src/api/tenantOnboarding/tenantOnboarding.ts}); link-death answers are 410 with a
 * machine-readable {@code reason} body (unknown tokens 404), which the client maps body-first.
 */
@RestController
@RequiredArgsConstructor
public class TenantAdminOnboardingController {

  /** Onboarding phase of a consumed-but-resumable link (#569 resume contract). */
  static final String PHASE_PENDING_2FA_ACTIVATION = "PENDING_2FA_ACTIVATION";

  private final @NonNull TenantAdminOnboardingService onboardingService;

  @GetMapping({
    "/users/account-invites/{token}/onboarding",
    "/service/users/account-invites/{token}/onboarding"
  })
  public ResponseEntity<TenantAdminOnboardingInviteResponseDTO> resolveOnboardingInvite(
      @PathVariable String token) {
    OnboardingInviteState state = onboardingService.resolveOnboardingInvite(token);
    return ResponseEntity.ok(TenantAdminOnboardingInviteResponseDTO.from(state));
  }

  @PostMapping({
    "/users/account-invites/{token}/onboarding/register",
    "/service/users/account-invites/{token}/onboarding/register"
  })
  public ResponseEntity<TenantAdminRegistrationResponseDTO> registerTenantAdmin(
      @PathVariable String token,
      @RequestBody(required = false) TenantAdminRegistrationRequestDTO request) {
    TenantAdminRegistrationResult result =
        onboardingService.registerTenantAdmin(token, toCommand(request));
    return ResponseEntity.ok(TenantAdminRegistrationResponseDTO.from(result));
  }

  @PostMapping({
    "/users/account-invites/{token}/onboarding/two-factor",
    "/service/users/account-invites/{token}/onboarding/two-factor"
  })
  public ResponseEntity<Void> activateTwoFactor(
      @PathVariable String token,
      @RequestBody(required = false) TwoFactorActivationRequestDTO request) {
    onboardingService.activateTwoFactor(token, request == null ? null : request.otp);
    return ResponseEntity.ok().build();
  }

  private static RegisterTenantAdminCommand toCommand(TenantAdminRegistrationRequestDTO request) {
    TenantAdminRegistrationRequestDTO safe =
        request == null ? new TenantAdminRegistrationRequestDTO() : request;
    OrganisationDataDTO organisation =
        safe.organisation == null ? new OrganisationDataDTO() : safe.organisation;
    DpaAcceptanceDataDTO dpa = safe.dpa == null ? new DpaAcceptanceDataDTO() : safe.dpa;
    AccountDataDTO account = safe.account == null ? new AccountDataDTO() : safe.account;
    return new RegisterTenantAdminCommand(
        organisation.name,
        organisation.subdomain,
        organisation.address,
        Boolean.TRUE.equals(dpa.accepted),
        dpa.signerName,
        dpa.signerPosition,
        dpa.signerEmail,
        dpa.signerOrganisation,
        account.password,
        safe.reservedTenantId,
        safe.tenantIdReservationToken);
  }

  public static class OrganisationDataDTO {
    public String name;
    public String subdomain;
    public String address;
  }

  public static class DpaAcceptanceDataDTO {
    public Boolean accepted;
    public String signerName;
    public String signerPosition;
    public String signerEmail;
    public String signerOrganisation;
  }

  public static class AccountDataDTO {
    public String password;
  }

  public static class TenantAdminRegistrationRequestDTO {
    public OrganisationDataDTO organisation;
    public DpaAcceptanceDataDTO dpa;
    public AccountDataDTO account;

    /** Echoed reservation pair proving ownership of the invite's tenant-ID reservation. */
    public Long reservedTenantId;

    public String tenantIdReservationToken;
  }

  public static class TwoFactorActivationRequestDTO {
    public String otp;
  }

  public static class TwoFactorSetupDTO {
    /** Base32 TOTP secret to show/link in the authenticator app. */
    public String secret;

    /** QR code PNG (base64) when Keycloak provides one; the client renders text-only when null. */
    public String qrCodeBase64;

    static TwoFactorSetupDTO of(String secret, String qrCodeBase64) {
      TwoFactorSetupDTO dto = new TwoFactorSetupDTO();
      dto.secret = secret;
      dto.qrCodeBase64 = qrCodeBase64;
      return dto;
    }
  }

  public static class TenantAdminOnboardingInviteResponseDTO {
    public String recipientEmail;
    public String firstName;
    public String lastName;

    /**
     * The tenant ID the invite reserved (TenantService {@code TenantIdReservationDTO.tenantId}).
     */
    public Long reservedTenantId;

    /** Reservation token echoed back on registration to consume the reservation atomically. */
    public String tenantIdReservationToken;

    public LocalDateTime expiresAt;

    /**
     * Published DPA/AVV text (JSON language -> HTML map). Not yet sourced server-side — the
     * operator-DPA lookup is the U9 follow-up; the Admin panel renders the step without the preview
     * text when null.
     */
    public String dpaContent;

    /** {@code PENDING_2FA_ACTIVATION} when the flow re-enters at the 2FA step; null otherwise. */
    public String phase;

    /** Re-issued TOTP setup material for a resumable link; null renders the verify-only variant. */
    public TwoFactorSetupDTO twoFactor;

    static TenantAdminOnboardingInviteResponseDTO from(OnboardingInviteState state) {
      AccountInvite invite = state.invite();
      TenantAdminOnboardingInviteResponseDTO dto = new TenantAdminOnboardingInviteResponseDTO();
      dto.recipientEmail = invite.getRecipientEmail();
      dto.firstName = invite.getFirstName();
      dto.lastName = invite.getLastName();
      dto.reservedTenantId = invite.getTenantId();
      dto.tenantIdReservationToken = invite.getTenantIdReservationToken();
      dto.expiresAt = invite.getExpiresAt();
      dto.dpaContent = null;
      if (state.pendingTwoFactorResume()) {
        dto.phase = PHASE_PENDING_2FA_ACTIVATION;
        if (invite.getTotpPendingSecret() != null) {
          // The raw token is the only credential, so re-showing the secret to the token holder
          // exposes nothing new; the QR code is not re-issued (secret-only re-entry).
          dto.twoFactor = TwoFactorSetupDTO.of(invite.getTotpPendingSecret(), null);
        }
      }
      return dto;
    }
  }

  public static class TenantAdminRegistrationResponseDTO {
    /** The created (inactive) tenant — equals the reserved ID. */
    public Long tenantId;

    public TwoFactorSetupDTO twoFactor;

    static TenantAdminRegistrationResponseDTO from(TenantAdminRegistrationResult result) {
      TenantAdminRegistrationResponseDTO dto = new TenantAdminRegistrationResponseDTO();
      dto.tenantId = result.tenantId();
      dto.twoFactor = TwoFactorSetupDTO.of(result.totpSecret(), result.totpQrCodeBase64());
      return dto;
    }
  }
}
