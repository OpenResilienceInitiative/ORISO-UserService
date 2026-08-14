package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService.CounsellorOnboardingState;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService.CounsellorRegistrationResult;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService.RegisterCounsellorCommand;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService.TopicOption;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantAdminOnboardingService;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantAdminOnboardingService.OnboardingInviteState;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantAdminOnboardingService.RegisterTenantAdminCommand;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantAdminOnboardingService.TenantAdminRegistrationResult;
import java.time.LocalDateTime;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * PUBLIC onboarding endpoints behind an invite link (#569 chain fix; extended by #997). One set of
 * routes serves BOTH invite roles that onboard on the Admin panel — the invite's {@code targetRole}
 * decides server-side which flow answers: {@code TENANT_ADMIN} (TEN-INV-U8, {@link
 * TenantAdminOnboardingService}) and {@code COUNSELLOR} (wizard, {@link
 * CounsellorOnboardingService}). All endpoints are anonymous by design — the invitee has no account
 * yet; the raw invite token in the path is the only credential. Mapped with and without the {@code
 * /service} prefix because the API gateway forwards the prefix unchanged (same convention as the
 * other public endpoints). Response/request shapes are pinned to the Admin panel clients ({@code
 * src/api/tenantOnboarding/tenantOnboarding.ts} and {@code
 * src/api/counsellorOnboarding/counsellorOnboarding.ts}); link-death answers are 410 with a
 * machine-readable {@code reason} body (unknown tokens 404), which the clients map body-first.
 */
@RestController
@RequiredArgsConstructor
public class TenantAdminOnboardingController {

  /** Onboarding phase of a consumed-but-resumable link (#569 resume contract). */
  static final String PHASE_PENDING_2FA_ACTIVATION = "PENDING_2FA_ACTIVATION";

  /** Phase of a completed registration whose invite carries no open 2FA gate. */
  static final String PHASE_COMPLETED = "COMPLETED";

  private final @NonNull TenantAdminOnboardingService onboardingService;
  private final @NonNull CounsellorOnboardingService counsellorOnboardingService;
  private final @NonNull AccountInviteService accountInviteService;

  @GetMapping({
    "/users/account-invites/{token}/onboarding",
    "/service/users/account-invites/{token}/onboarding"
  })
  public ResponseEntity<TenantAdminOnboardingInviteResponseDTO> resolveOnboardingInvite(
      @PathVariable String token) {
    if (targetRoleOf(token) == AccountInviteTargetRole.COUNSELLOR) {
      CounsellorOnboardingState state = counsellorOnboardingService.resolveOnboardingInvite(token);
      return ResponseEntity.ok(TenantAdminOnboardingInviteResponseDTO.from(state));
    }
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
    if (targetRoleOf(token) == AccountInviteTargetRole.COUNSELLOR) {
      CounsellorRegistrationResult result =
          counsellorOnboardingService.registerCounsellor(token, toCounsellorCommand(request));
      return ResponseEntity.ok(TenantAdminRegistrationResponseDTO.from(result));
    }
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
    String otp = request == null ? null : request.otp;
    if (targetRoleOf(token) == AccountInviteTargetRole.COUNSELLOR) {
      counsellorOnboardingService.activateTwoFactor(token, otp);
    } else {
      onboardingService.activateTwoFactor(token, otp);
    }
    return ResponseEntity.ok().build();
  }

  /**
   * Role probe deciding which onboarding flow answers (unknown tokens 404 here already). The
   * role-specific services re-validate the role themselves, so a mismatch can never slip through.
   */
  private AccountInviteTargetRole targetRoleOf(String token) {
    return accountInviteService.findInviteByToken(token).getTargetRole();
  }

  private static RegisterCounsellorCommand toCounsellorCommand(
      TenantAdminRegistrationRequestDTO request) {
    TenantAdminRegistrationRequestDTO safe =
        request == null ? new TenantAdminRegistrationRequestDTO() : request;
    AccountDataDTO account = safe.account == null ? new AccountDataDTO() : safe.account;
    PersonDataDTO person = safe.person == null ? new PersonDataDTO() : safe.person;
    DisplayNamesDataDTO names = safe.names == null ? new DisplayNamesDataDTO() : safe.names;
    return new RegisterCounsellorCommand(
        account.username,
        account.password,
        person.salutation,
        person.position,
        person.title,
        names.publicName,
        names.internalDisplayName,
        safe.topicIds);
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
    /** Only used by the counsellor wizard (#997); tenant admins log in with their email. */
    public String username;

    public String password;
  }

  /** Counsellor wizard "Person" step fields (#994 stable salutation keys). */
  public static class PersonDataDTO {
    public String salutation;
    public String position;
    public String title;
  }

  /** Counsellor wizard "Name" step fields (#996 dual display name). */
  public static class DisplayNamesDataDTO {
    public String publicName;
    public String internalDisplayName;
  }

  public static class TenantAdminRegistrationRequestDTO {
    public OrganisationDataDTO organisation;
    public DpaAcceptanceDataDTO dpa;
    public AccountDataDTO account;

    /** Echoed reservation pair proving ownership of the invite's tenant-ID reservation. */
    public Long reservedTenantId;

    public String tenantIdReservationToken;

    /** Counsellor wizard (#997) only. */
    public PersonDataDTO person;

    public DisplayNamesDataDTO names;

    /** Counsellor wizard topic selection — validated against the invite's coverage. */
    public List<Long> topicIds;
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

  /** A selectable topic of a counsellor invite's coverage (#997). */
  public static class TopicOptionDTO {
    public Long id;
    public String name;

    static TopicOptionDTO from(TopicOption option) {
      TopicOptionDTO dto = new TopicOptionDTO();
      dto.id = option.id();
      dto.name = option.name();
      return dto;
    }
  }

  public static class TenantAdminOnboardingInviteResponseDTO {
    /** The invite's target role — tells the client which wizard variant runs (#997). */
    public String targetRole;

    public String recipientEmail;
    public String firstName;
    public String lastName;

    /** Counsellor invites only (#997): the tenant/agency/department routing of the invite. */
    public Long tenantId;

    public Long agencyId;
    public Long departmentId;

    /** Counsellor invites only (#997): topics the wizard's topic step may offer. */
    public List<TopicOptionDTO> topics;

    /**
     * The tenant ID the invite reserved (TenantService {@code TenantIdReservationDTO.tenantId}).
     */
    public Long reservedTenantId;

    /** Reservation token echoed back on registration to consume the reservation atomically. */
    public String tenantIdReservationToken;

    public LocalDateTime expiresAt;

    /**
     * The platform operator's published DPA/AVV text (stored JSON language -> HTML map, same format
     * as the legal settings), rendered read-only on the onboarding DPA step so the invitee sees —
     * and can navigate via the anchor/TOC — the contract they confirm. Null only when the operator
     * published no DPA or the lookup was unavailable; the Admin panel then falls back to its "text
     * will be provided by the platform operator" hint.
     */
    public String dpaContent;

    /** {@code PENDING_2FA_ACTIVATION} when the flow re-enters at the 2FA step; null otherwise. */
    public String phase;

    /** Re-issued TOTP setup material for a resumable link; null renders the verify-only variant. */
    public TwoFactorSetupDTO twoFactor;

    static TenantAdminOnboardingInviteResponseDTO from(OnboardingInviteState state) {
      AccountInvite invite = state.invite();
      TenantAdminOnboardingInviteResponseDTO dto = new TenantAdminOnboardingInviteResponseDTO();
      dto.targetRole = AccountInviteTargetRole.TENANT_ADMIN.name();
      dto.recipientEmail = invite.getRecipientEmail();
      dto.firstName = invite.getFirstName();
      dto.lastName = invite.getLastName();
      dto.reservedTenantId = invite.getTenantId();
      dto.tenantIdReservationToken = invite.getTenantIdReservationToken();
      dto.expiresAt = invite.getExpiresAt();
      dto.dpaContent = state.dpaContent();
      applyTwoFactorResume(dto, invite, state.pendingTwoFactorResume());
      return dto;
    }

    static TenantAdminOnboardingInviteResponseDTO from(CounsellorOnboardingState state) {
      AccountInvite invite = state.invite();
      TenantAdminOnboardingInviteResponseDTO dto = new TenantAdminOnboardingInviteResponseDTO();
      dto.targetRole = AccountInviteTargetRole.COUNSELLOR.name();
      dto.recipientEmail = invite.getRecipientEmail();
      dto.firstName = invite.getFirstName();
      dto.lastName = invite.getLastName();
      dto.tenantId = invite.getTenantId();
      dto.agencyId = invite.getAgencyId();
      dto.departmentId = invite.getDepartmentId();
      dto.topics = state.topics().stream().map(TopicOptionDTO::from).toList();
      dto.expiresAt = invite.getExpiresAt();
      applyTwoFactorResume(dto, invite, state.pendingTwoFactorResume());
      return dto;
    }

    private static void applyTwoFactorResume(
        TenantAdminOnboardingInviteResponseDTO dto, AccountInvite invite, boolean resume) {
      if (resume) {
        dto.phase = PHASE_PENDING_2FA_ACTIVATION;
        if (invite.getTotpPendingSecret() != null) {
          // The raw token is the only credential, so re-showing the secret to the token holder
          // exposes nothing new; the QR code is not re-issued (secret-only re-entry).
          dto.twoFactor = TwoFactorSetupDTO.of(invite.getTotpPendingSecret(), null);
        }
      }
    }
  }

  public static class TenantAdminRegistrationResponseDTO {
    /** The created (inactive) tenant — equals the reserved ID. Tenant-admin registrations only. */
    public Long tenantId;

    /** The created consultant. Counsellor registrations (#997) only. */
    public String consultantId;

    /**
     * Counsellor registrations only: {@code PENDING_2FA_ACTIVATION} while the mandatory TOTP
     * activation is open, {@code COMPLETED} when the invite's 2FA gate was waived — the wizard then
     * skips the 2FA step. Tenant-admin registrations always continue with the 2FA step.
     */
    public String phase;

    public TwoFactorSetupDTO twoFactor;

    static TenantAdminRegistrationResponseDTO from(TenantAdminRegistrationResult result) {
      TenantAdminRegistrationResponseDTO dto = new TenantAdminRegistrationResponseDTO();
      dto.tenantId = result.tenantId();
      dto.twoFactor = TwoFactorSetupDTO.of(result.totpSecret(), result.totpQrCodeBase64());
      return dto;
    }

    static TenantAdminRegistrationResponseDTO from(CounsellorRegistrationResult result) {
      TenantAdminRegistrationResponseDTO dto = new TenantAdminRegistrationResponseDTO();
      dto.consultantId = result.consultantId();
      dto.phase = result.twoFactorRequired() ? PHASE_PENDING_2FA_ACTIVATION : PHASE_COMPLETED;
      if (result.totpSecret() != null) {
        dto.twoFactor = TwoFactorSetupDTO.of(result.totpSecret(), result.totpQrCodeBase64());
      }
      return dto;
    }
  }
}
