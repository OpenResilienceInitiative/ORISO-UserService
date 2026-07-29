package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.port.out.IdentityProfileLookup;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteLinkException;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.MultilingualTenantDTO;
import java.time.LocalDateTime;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public tenant-admin onboarding behind an invite link (#569 chain fix, UserService side of the
 * Admin panel's TEN-INV-U8 flow, ORISO-Admin#571).
 *
 * <p>Contract pinned by the Admin panel client ({@code src/api/tenantOnboarding/tenantOnboarding
 * .ts}): resolve returns the invite state keyed by the raw link token; register creates the
 * still-inactive tenant (consuming the invite's TenantService tenant-ID reservation atomically via
 * {@code MultilingualTenantDTO.tenantIdReservationToken}) plus the tenant-admin account and returns
 * TOTP setup material; two-factor confirms the TOTP setup with a first one-time password. Links are
 * strictly single-use, but stay resumable at the 2FA step while the mandatory activation is pending
 * and the link is unexpired (resume contract shared with the public accept endpoint).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantAdminOnboardingService {

  private static final int MIN_PASSWORD_LENGTH = 8;

  private final @NonNull AccountInviteRepository accountInviteRepository;
  private final @NonNull AccountInviteService accountInviteService;
  private final @NonNull CreateAdminService createAdminService;
  private final @NonNull IdentityAccountRemover identityAccountRemover;
  private final @NonNull IdentityProfileLookup identityProfileLookup;
  private final @NonNull IdentitySecondFactor identitySecondFactor;
  private final @NonNull TenantCreationClient tenantCreationClient;
  private final @NonNull UsernameTranscoder usernameTranscoder;

  /**
   * Resolves the invite state for a raw link token. Mirrors the accept endpoint's state machine: a
   * deliverable ({@code EMAIL_SENT}) unexpired invite resolves plainly; a consumed invite whose
   * mandatory 2FA activation is still open and whose link is unexpired resolves with a pending
   * two-factor resume; every other state maps to the distinct link-death reasons (410 body {@code
   * reason}, unknown tokens 404).
   */
  @Transactional
  public OnboardingInviteState resolveOnboardingInvite(String rawToken) {
    AccountInvite invite = findTenantAdminInvite(rawToken);
    LocalDateTime now = LocalDateTime.now();

    if (invite.getStatus() == AccountInviteStatus.EMAIL_SENT) {
      expireIfPastExpiry(invite, now);
      return new OnboardingInviteState(invite, false);
    }
    if (isResumableAtTwoFactorStep(invite, now)) {
      return new OnboardingInviteState(invite, true);
    }
    throw linkDeathException(invite);
  }

  /**
   * Creates the inactive tenant plus the tenant-admin account and consumes the tenant-ID
   * reservation atomically; strictly single-use (atomic claim). Ordering keeps the external side
   * effects compensable: the invite is claimed first (rolls back with the transaction), then the
   * Keycloak account is created (compensated via {@code rollbackUser} on any later failure), and
   * the TenantService creation — the irreversible reservation consumption — runs last.
   */
  @Transactional
  public TenantAdminRegistrationResult registerTenantAdmin(
      String rawToken, RegisterTenantAdminCommand command) {
    validateRegistration(command);
    AccountInvite invite = findTenantAdminInvite(rawToken);
    LocalDateTime now = LocalDateTime.now();

    if (invite.getStatus() != AccountInviteStatus.EMAIL_SENT) {
      throw linkDeathException(invite);
    }
    expireIfPastExpiry(invite, now);
    if (invite.getTenantId() == null || isBlank(invite.getTenantIdReservationToken())) {
      // Legacy invite created while TenantService lacked the TEN-INV-U1 allocation endpoints —
      // a reservation-consuming creation is impossible without the authoritative ledger.
      throw new InternalServerErrorException(
          "Invite holds no authoritative tenant-ID reservation — re-issue the invite after"
              + " deploying the TenantService allocation endpoints (TEN-INV-U1)");
    }
    if (!invite.getTenantId().equals(command.reservedTenantId())
        || !invite.getTenantIdReservationToken().equals(command.tenantIdReservationToken())) {
      // The echoed reservation pair must prove ownership; a mismatch is an unusable link (404).
      throw new NotFoundException("Reservation does not match this invite");
    }

    int claimed = accountInviteRepository.claimForAcceptance(invite.getId(), null, now);
    if (claimed == 0) {
      // Lost the single-use race — report the winner's committed state.
      AccountInvite current =
          accountInviteRepository
              .findById(invite.getId())
              .orElseThrow(() -> new NotFoundException("Account invite not found"));
      throw linkDeathException(current);
    }

    var admin = createAdminService.createNewTenantAdmin(buildAdminDto(invite, command));
    try {
      IdentityOtpCredential otpInfo =
          identitySecondFactor.getOtpCredential(
              usernameTranscoder.encodeUsername(admin.getUsername()));
      if (otpInfo == null || isBlank(otpInfo.secret())) {
        throw new InternalServerErrorException(
            "Keycloak issued no TOTP setup material for the onboarding account");
      }

      AccountInvite claimedInvite =
          accountInviteRepository
              .findById(invite.getId())
              .orElseThrow(() -> new NotFoundException("Account invite not found"));
      claimedInvite.setAcceptedByUserId(admin.getId());
      claimedInvite.setTotpPendingSecret(otpInfo.secret());
      claimedInvite.setUpdateDate(now);
      accountInviteRepository.save(claimedInvite);

      // DPA acceptance snapshot: the wording is rendered read-only by the Admin panel; full
      // signature persistence in TenantService is the U9 follow-up. Log for the audit trail.
      log.info(
          "Tenant-admin onboarding registration accepted the DPA (invite {}, signer '{}')",
          invite.getId(),
          command.dpaSignerName());

      MultilingualTenantDTO created =
          tenantCreationClient.createTenant(buildTenantDto(invite, command));
      Long tenantId =
          created != null && created.getId() != null ? created.getId() : invite.getTenantId();
      return new TenantAdminRegistrationResult(tenantId, otpInfo.secret(), otpInfo.secretQrCode());
    } catch (RuntimeException exception) {
      // Every database change rolls back with the exception; the Keycloak account is external
      // state and must be compensated explicitly so a failed registration stays retryable.
      identityAccountRemover.rollbackUser(admin.getId());
      throw exception;
    }
  }

  /**
   * Confirms the pending TOTP setup with a first one-time password. An invalid or rejected code
   * answers 400 (the Admin panel maps 400/422 to its invalid-code state); once the gate is
   * satisfied the link is terminally consumed.
   */
  @Transactional
  public void activateTwoFactor(String rawToken, String oneTimePassword) {
    if (isBlank(oneTimePassword)) {
      throw new BadRequestException("otp is required");
    }
    AccountInvite invite = findTenantAdminInvite(rawToken);
    LocalDateTime now = LocalDateTime.now();

    if (invite.getStatus() == AccountInviteStatus.EMAIL_SENT) {
      throw new BadRequestException("Registration has not happened yet for this invite");
    }
    if (invite.getStatus() != AccountInviteStatus.ACCEPTED) {
      throw linkDeathException(invite);
    }
    if (!isResumableAtTwoFactorStep(invite, now)) {
      // Gate already satisfied or resume window expired — terminally consumed.
      throw new AccountInviteLinkException(AccountInviteLinkException.Reason.CONSUMED);
    }
    if (isBlank(invite.getTotpPendingSecret()) || isBlank(invite.getAcceptedByUserId())) {
      throw new BadRequestException("No pending TOTP setup exists for this invite");
    }

    var identityProfile =
        identityProfileLookup
            .findById(invite.getAcceptedByUserId())
            .orElseThrow(() -> new NotFoundException("Onboarding identity not found"));
    boolean valid =
        identitySecondFactor.setUpOtpCredential(
            identityProfile.username(), oneTimePassword.trim(), invite.getTotpPendingSecret());
    if (!valid) {
      throw new BadRequestException("Invalid one-time password");
    }

    accountInviteService.markTwoFactorActive(invite.getAcceptedByUserId());
    invite.setTotpPendingSecret(null);
    invite.setUpdateDate(now);
    accountInviteRepository.save(invite);
  }

  private AccountInvite findTenantAdminInvite(String rawToken) {
    if (isBlank(rawToken)) {
      throw new BadRequestException("Invite token is required");
    }
    AccountInvite invite =
        accountInviteRepository
            .findByTokenHash(AccountInviteService.hash(rawToken))
            .orElseThrow(() -> new NotFoundException("Account invite not found"));
    if (invite.getTargetRole() != AccountInviteTargetRole.TENANT_ADMIN) {
      // Tokens of other roles must not resolve on the tenant-admin onboarding endpoints.
      throw new NotFoundException("Account invite not found");
    }
    return invite;
  }

  private void expireIfPastExpiry(AccountInvite invite, LocalDateTime now) {
    if (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(now)) {
      invite.setStatus(AccountInviteStatus.EXPIRED);
      invite.setUpdateDate(now);
      accountInviteRepository.save(invite);
      throw new AccountInviteLinkException(AccountInviteLinkException.Reason.EXPIRED);
    }
  }

  private static boolean isResumableAtTwoFactorStep(AccountInvite invite, LocalDateTime now) {
    boolean twoFactorStillPending =
        !AccountInviteService.isTwoFactorGateSatisfied(invite.getTwoFactorStatus());
    boolean withinExpiryWindow =
        invite.getExpiresAt() == null || !invite.getExpiresAt().isBefore(now);
    return invite.getStatus() == AccountInviteStatus.ACCEPTED
        && twoFactorStillPending
        && withinExpiryWindow;
  }

  private static AccountInviteLinkException linkDeathException(AccountInvite invite) {
    return switch (invite.getStatus()) {
      case ACCEPTED -> new AccountInviteLinkException(AccountInviteLinkException.Reason.CONSUMED);
      case REVOKED -> new AccountInviteLinkException(AccountInviteLinkException.Reason.REVOKED);
      case SUPERSEDED ->
          new AccountInviteLinkException(AccountInviteLinkException.Reason.SUPERSEDED);
      case EXPIRED -> new AccountInviteLinkException(AccountInviteLinkException.Reason.EXPIRED);
      default -> new AccountInviteLinkException(AccountInviteLinkException.Reason.NOT_ACTIVE);
    };
  }

  private static void validateRegistration(RegisterTenantAdminCommand command) {
    if (command == null) {
      throw new BadRequestException("Request body is required");
    }
    if (isBlank(command.organisationName())) {
      throw new BadRequestException("organisation.name is required");
    }
    if (isBlank(command.password()) || command.password().length() < MIN_PASSWORD_LENGTH) {
      throw new BadRequestException(
          "account.password must be at least " + MIN_PASSWORD_LENGTH + " characters long");
    }
    if (!command.dpaAccepted()) {
      throw new BadRequestException("The data processing agreement must be accepted");
    }
  }

  private CreateAdminDTO buildAdminDto(AccountInvite invite, RegisterTenantAdminCommand command) {
    CreateAdminDTO adminDto = new CreateAdminDTO();
    adminDto.setUsername(invite.getRecipientEmail());
    adminDto.setEmail(invite.getRecipientEmail());
    // The Admin entity requires non-null names, but invite names are optional — fall back to the
    // email local part so nameless invites still onboard (the admin can correct the name later).
    adminDto.setFirstname(
        isBlank(invite.getFirstName())
            ? emailLocalPart(invite.getRecipientEmail())
            : invite.getFirstName());
    adminDto.setLastname(isBlank(invite.getLastName()) ? "Admin" : invite.getLastName());
    adminDto.setPassword(command.password());
    adminDto.setTenantId(invite.getTenantId().intValue());
    return adminDto;
  }

  private static String emailLocalPart(String email) {
    int atIndex = email.indexOf('@');
    return atIndex > 0 ? email.substring(0, atIndex) : email;
  }

  private static MultilingualTenantDTO buildTenantDto(
      AccountInvite invite, RegisterTenantAdminCommand command) {
    return new MultilingualTenantDTO()
        .id(invite.getTenantId())
        .name(command.organisationName().trim())
        .subdomain(trimToNull(command.subdomain()))
        .address(trimToNull(command.address()))
        .adminEmails(List.of(invite.getRecipientEmail()))
        .tenantIdReservationToken(invite.getTenantIdReservationToken());
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static String trimToNull(String value) {
    return isBlank(value) ? null : value.trim();
  }

  /**
   * Resolved onboarding state: the invite plus whether the flow re-enters at the 2FA step (#569
   * resume contract) instead of the registration step.
   */
  public record OnboardingInviteState(AccountInvite invite, boolean pendingTwoFactorResume) {}

  /** Input for the reservation-consuming registration; mirrors the Admin panel request shape. */
  public record RegisterTenantAdminCommand(
      String organisationName,
      String subdomain,
      String address,
      boolean dpaAccepted,
      String dpaSignerName,
      String dpaSignerPosition,
      String dpaSignerEmail,
      String dpaSignerOrganisation,
      String password,
      Long reservedTenantId,
      String tenantIdReservationToken) {}

  /** The created (inactive) tenant plus the TOTP setup material for the 2FA step. */
  public record TenantAdminRegistrationResult(
      Long tenantId, String totpSecret, String totpQrCodeBase64) {}
}
