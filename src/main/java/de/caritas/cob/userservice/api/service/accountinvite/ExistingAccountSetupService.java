package de.caritas.cob.userservice.api.service.accountinvite;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordChangeRequirement;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.port.out.IdentityProfileLookup;
import de.caritas.cob.userservice.api.port.out.IdentityRoleLookup;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Single-use password handover for an already persisted admin or counsellor identity. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExistingAccountSetupService {

  private static final AccountInvitePurpose SETUP = AccountInvitePurpose.EXISTING_ACCOUNT_SETUP;
  private static final AccountInviteStatus SENT = AccountInviteStatus.EMAIL_SENT;
  private static final AccountInviteProvisioningStatus IN_PROGRESS =
      AccountInviteProvisioningStatus.IN_PROGRESS;

  private final @NonNull AccountInviteRepository invites;
  private final @NonNull AdminRepository admins;
  private final @NonNull ConsultantRepository consultants;
  private final @NonNull IdentityProfileLookup identities;
  private final @NonNull IdentityRoleLookup roles;
  private final @NonNull IdentityPasswordChangeRequirement passwordChangeRequirement;
  private final @NonNull InitialPasswordVerifier initialPasswords;
  private final @NonNull IdentityPasswordUpdater passwords;
  private final @NonNull PlatformTransactionManager transactions;

  @Value("${multitenancy.enabled:true}")
  private boolean multitenancyEnabled;

  /** A direct creator proves the initial native projection with its exact owned durable receipt. */
  public void requireSameCreatedIdentity(
      String id,
      AccountInviteTargetRole role,
      Long tenant,
      String email,
      String username,
      de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands
              .AccountProjection
          projection,
      boolean requireEnabled) {
    var target = new SetupTarget(null, id, role, tenant, email, username, null, null);
    requireSameSavedIdentity(target);
    String requiredRole =
        switch (role) {
          case TENANT_ADMIN -> "tenant-admin";
          case AGENCY_ADMIN -> "restricted-agency-admin";
          case COUNSELLOR -> "consultant";
          default -> throw new ConflictException("Unsupported created setup role");
        };
    if (projection == null
        || !id.equals(projection.id())
        || !java.util.Objects.equals(tenant, projection.tenantId())
        || !sameEmail(email, projection.email())
        || !sameCanonicalUsername(username, projection.username())
        || !projection.roles().contains(requiredRole)
        || !projection.passwordChangeRequired()
        || (requireEnabled && !projection.enabled()))
      throw new ConflictException("Created account setup binding is no longer current");
  }

  /** Issuance preflight; confirmation makes the same fresh check before changing a password. */
  public void requireSameCurrentIdentity(
      String identityId,
      AccountInviteTargetRole role,
      Long tenantId,
      String email,
      String username) {
    try {
      requireSameCurrentIdentity(
          new SetupTarget(null, identityId, role, tenantId, email, username, null, null));
    } catch (StaleIdentityException stale) {
      throw new ConflictException(
          "SETUP_TEMPORARY_PASSWORD_REPLACED".equals(stale.reason)
              ? "Existing account no longer requires this setup link"
              : "Existing account setup binding is no longer current");
    } catch (RuntimeException unavailable) {
      throw new IllegalStateException("Account setup authority is unavailable");
    }
  }

  /**
   * The emailed token is the only credential for this endpoint. It never accepts an identity,
   * tenant, role or initial password from the browser. The claim commits before any Keycloak call;
   * an uncertain password outcome stays claimed for operator investigation. It cannot be reissued
   * until the operator proves the remote outcome and safely resolves the held claim.
   */
  public void confirm(String rawToken, String chosenPassword) {
    if (isBlank(rawToken) || isBlank(chosenPassword)) {
      throw new BadRequestException("Setup link and chosen password are required");
    }
    SetupTarget target = inTransaction(() -> claim(rawToken));
    try {
      requireSameCurrentIdentity(target);
    } catch (StaleIdentityException stale) {
      inTransaction(
          () -> {
            invites.revokeStaleExistingAccountSetup(
                target.inviteId(),
                SETUP,
                SENT,
                IN_PROGRESS,
                AccountInviteStatus.REVOKED,
                stale.reason,
                LocalDateTime.now());
            return null;
          });
      throw new AccountInviteLinkException(AccountInviteLinkException.Reason.REVOKED);
    } catch (RuntimeException unavailable) {
      releaseDefinitive(target.inviteId(), "SETUP_AUTHORITY_UNAVAILABLE");
      throw new IllegalStateException("Account setup authority is unavailable");
    }

    // Temporary Keycloak credentials carry a required action, so password-grant verification
    // cannot tell whether the new password equals the old one. A per-link salted verifier can.
    boolean unchanged;
    try {
      unchanged = initialPasswords.matches(chosenPassword, target.initialPasswordVerifier());
    } catch (RuntimeException invalidVerifier) {
      recordIndeterminate(target.inviteId());
      throw new IllegalStateException("Account setup verifier needs operator review");
    }
    if (unchanged) {
      releaseDefinitive(target.inviteId(), "SETUP_PASSWORD_UNCHANGED");
      throw new BadRequestException("Choose a password different from the initial password");
    }

    try {
      passwords.updatePassword(
          target.identityId(),
          chosenPassword,
          de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
              .checkedSetupPassword(target.claimedInvite(), chosenPassword, initialPasswords));
    } catch (CustomValidationHttpStatusException rejected) {
      // Keycloak positively rejected the new credential before changing it.
      releaseDefinitive(target.inviteId(), "SETUP_PASSWORD_REJECTED");
      throw rejected;
    } catch (RuntimeException uncertain) {
      recordIndeterminate(target.inviteId());
      throw new IllegalStateException(
          "Password setup outcome is indeterminate; operator review is required");
    }

    try {
      inTransaction(
          () -> {
            // A changed account during the remote call must not silently complete the old link.
            requireSameSavedIdentity(target);
            if (target.role() == AccountInviteTargetRole.COUNSELLOR) {
              consultants
                  .findByIdAndDeleteDateIsNull(target.identityId())
                  .ifPresent(
                      c -> {
                        c.setPasswordChangeRequired(false);
                        consultants.save(c);
                      });
            }
            int completed =
                invites.completeExistingAccountSetup(
                    target.inviteId(),
                    SETUP,
                    SENT,
                    IN_PROGRESS,
                    AccountInviteStatus.ACCEPTED,
                    AccountInviteProvisioningStatus.COMPLETED,
                    EmailVerificationStatus.VERIFIED,
                    target.identityId(),
                    LocalDateTime.now());
            if (completed != 1) {
              throw new IllegalStateException(
                  "Existing-account setup claim could not be finalized");
            }
            return null;
          });
    } catch (RuntimeException finalizationFailure) {
      recordIndeterminate(target.inviteId());
      throw new IllegalStateException("Password changed but setup needs operator review");
    }
  }

  /**
   * Scheduled expiry makes old links unusable while retaining private recovery data for reissue.
   */
  public int expireElapsedLinks() {
    return inTransaction(
        () ->
            invites.expireElapsedExistingAccountSetup(
                SETUP,
                List.of(AccountInviteStatus.DRAFT, SENT),
                AccountInviteStatus.EXPIRED,
                IN_PROGRESS,
                LocalDateTime.now()));
  }

  private SetupTarget claim(String rawToken) {
    AccountInvite invite =
        invites
            .findByTokenHash(AccountInviteService.hash(rawToken))
            .orElseThrow(() -> new NotFoundException("Account setup link not found"));
    if (invite.getPurpose() != SETUP) {
      throw new BadRequestException("This link is for account provisioning");
    }
    if (invite.getStatus() != SENT) {
      throw new AccountInviteLinkException(
          switch (invite.getStatus()) {
            case EXPIRED -> AccountInviteLinkException.Reason.EXPIRED;
            case REVOKED -> AccountInviteLinkException.Reason.REVOKED;
            case SUPERSEDED -> AccountInviteLinkException.Reason.SUPERSEDED;
            case ACCEPTED -> AccountInviteLinkException.Reason.CONSUMED;
            default -> AccountInviteLinkException.Reason.NOT_ACTIVE;
          });
    }
    if (invite.getProvisioningStatus() == IN_PROGRESS) {
      throw new AccountInviteLinkException(
          "SETUP_OUTCOME_INDETERMINATE".equals(invite.getProvisioningFailureReason())
              ? AccountInviteLinkException.Reason.SETUP_OPERATOR_REVIEW_REQUIRED
              : AccountInviteLinkException.Reason.SETUP_IN_PROGRESS);
    }
    LocalDateTime now = LocalDateTime.now();
    if (invite.getExpiresAt() == null || !invite.getExpiresAt().isAfter(now)) {
      throw new AccountInviteLinkException(AccountInviteLinkException.Reason.EXPIRED);
    }
    if (isBlank(invite.getProvisionedUserId())
        || isBlank(invite.getSetupBoundUsername())
        || (multitenancyEnabled && invite.getTenantId() == null)
        || invite.getTargetRole() == null
        || isBlank(invite.getRecipientEmail())
        || isBlank(invite.getInitialPasswordVerifier())) {
      throw new BadRequestException("Account setup binding is incomplete");
    }
    if (invite.getTargetRole() != AccountInviteTargetRole.TENANT_ADMIN
        && invite.getTargetRole() != AccountInviteTargetRole.AGENCY_ADMIN
        && invite.getTargetRole() != AccountInviteTargetRole.COUNSELLOR) {
      throw new BadRequestException("Account setup role is not supported");
    }
    int won =
        invites.claimExistingAccountSetup(
            invite.getId(),
            SETUP,
            SENT,
            List.of(
                AccountInviteProvisioningStatus.PENDING, AccountInviteProvisioningStatus.FAILED),
            IN_PROGRESS,
            now);
    if (won != 1) {
      throw new ConflictException("Account setup is already being processed");
    }
    invite.setProvisioningStatus(IN_PROGRESS);
    return new SetupTarget(
        invite.getId(),
        invite.getProvisionedUserId(),
        invite.getTargetRole(),
        invite.getTenantId(),
        invite.getRecipientEmail(),
        usernameOf(invite),
        invite.getInitialPasswordVerifier(),
        invite);
  }

  private String requireSameCurrentIdentity(SetupTarget target) {
    requireSameSavedIdentity(target);
    var origin =
        target.claimedInvite() == null
            ? null
            : de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization
                .claimedSetupRead(target.claimedInvite());
    var profile =
        (origin == null
                ? identities.findById(target.identityId())
                : identities.findById(target.identityId(), origin))
            .orElseThrow(StaleIdentityException::new);
    if (!target.identityId().equals(profile.id())
        || !sameEmail(target.email(), profile.email())
        || !sameCanonicalUsername(target.username(), profile.username())) {
      throw new StaleIdentityException();
    }
    String requiredRole =
        switch (target.role()) {
          case TENANT_ADMIN -> "tenant-admin";
          case AGENCY_ADMIN -> "restricted-agency-admin";
          case COUNSELLOR -> "consultant";
          default -> throw new StaleIdentityException();
        };
    if (!(origin == null
            ? roles.findAllByUserId(target.identityId())
            : roles.findAllByUserId(target.identityId(), origin))
        .contains(requiredRole)) {
      throw new StaleIdentityException();
    }
    // A normal password reset can replace the temporary credential independently of this link.
    // Once Keycloak has removed UPDATE_PASSWORD, the old setup token cannot reset it again.
    if (!(origin == null
        ? passwordChangeRequirement.requiresPasswordChange(target.identityId())
        : passwordChangeRequirement.requiresPasswordChange(target.identityId(), origin))) {
      throw new StaleIdentityException("SETUP_TEMPORARY_PASSWORD_REPLACED");
    }
    return profile.username();
  }

  private boolean sameCanonicalUsername(String savedUsername, String identityUsername) {
    if (savedUsername == null || identityUsername == null) {
      return false;
    }
    try {
      var transcoder = new UsernameTranscoder();
      // Admin rows contain the plain login; counsellor rows contain its encoded form. Keycloak
      // can return either form. Match the existing UserHelper canonical rule, but require a valid
      // round trip so a malformed enc.* value cannot authorize a setup link.
      String saved = canonicalEncodedUsername(transcoder, savedUsername);
      String current = canonicalEncodedUsername(transcoder, identityUsername);
      return saved != null && saved.equals(current);
    } catch (RuntimeException malformedUsername) {
      return false;
    }
  }

  private String canonicalEncodedUsername(UsernameTranscoder transcoder, String username) {
    String encoded = transcoder.encodeUsername(username);
    String decoded = transcoder.decodeUsername(encoded);
    if (decoded.isBlank() || !encoded.equalsIgnoreCase(transcoder.encodeUsername(decoded))) {
      return null;
    }
    return encoded.toLowerCase(Locale.ROOT);
  }

  private void requireSameSavedIdentity(SetupTarget target) {
    TenantData currentTenant = TenantContext.getCurrentTenantData();
    TenantData oldTenant =
        currentTenant == null
            ? null
            : new TenantData(currentTenant.getTenantId(), currentTenant.getSubdomain());
    TenantContext.setCurrentTenant(target.tenantId());
    try {
      if (target.role() == AccountInviteTargetRole.COUNSELLOR) {
        var consultant =
            consultants
                .findByIdAndDeleteDateIsNull(target.identityId())
                .orElseThrow(StaleIdentityException::new);
        if (!Objects.equals(target.tenantId(), consultant.getTenantId())
            || !sameEmail(target.email(), consultant.getEmail())
            || !target.username().equals(consultant.getUsername())) {
          throw new StaleIdentityException();
        }
      } else {
        Admin.AdminType type =
            target.role() == AccountInviteTargetRole.TENANT_ADMIN
                ? Admin.AdminType.TENANT
                : Admin.AdminType.AGENCY;
        var admin =
            admins
                .findByIdAndType(target.identityId(), type)
                .orElseThrow(StaleIdentityException::new);
        if (!Objects.equals(target.tenantId(), admin.getTenantId())
            || !sameEmail(target.email(), admin.getEmail())
            || !target.username().equals(admin.getUsername())) {
          throw new StaleIdentityException();
        }
      }
    } finally {
      if (oldTenant == null) {
        TenantContext.clear();
      } else {
        TenantContext.setCurrentTenantData(oldTenant);
      }
    }
  }

  private void releaseDefinitive(Long inviteId, String reason) {
    inTransaction(
        () -> {
          invites.releaseExistingAccountSetup(
              inviteId,
              SETUP,
              SENT,
              IN_PROGRESS,
              AccountInviteProvisioningStatus.FAILED,
              reason,
              LocalDateTime.now());
          return null;
        });
  }

  private void recordIndeterminate(Long inviteId) {
    try {
      inTransaction(
          () -> {
            invites.recordIndeterminateExistingAccountSetup(
                inviteId,
                SETUP,
                SENT,
                IN_PROGRESS,
                "SETUP_OUTCOME_INDETERMINATE",
                LocalDateTime.now());
            return null;
          });
    } catch (RuntimeException persistenceFailure) {
      log.error(
          "Existing-account setup {} needs operator review; outcome audit unavailable ({})",
          inviteId,
          persistenceFailure.getClass().getSimpleName());
    }
  }

  private <T> T inTransaction(Supplier<T> action) {
    return new TransactionTemplate(transactions).execute(status -> action.get());
  }

  private static boolean sameEmail(String left, String right) {
    return left != null
        && right != null
        && left.trim().toLowerCase(Locale.ROOT).equals(right.trim().toLowerCase(Locale.ROOT));
  }

  private static String usernameOf(AccountInvite invite) {
    return invite.getSetupBoundUsername();
  }

  private record SetupTarget(
      Long inviteId,
      String identityId,
      AccountInviteTargetRole role,
      Long tenantId,
      String email,
      String username,
      String initialPasswordVerifier,
      AccountInvite claimedInvite) {}

  private static final class StaleIdentityException extends RuntimeException {
    private final String reason;

    private StaleIdentityException() {
      this("SETUP_IDENTITY_CHANGED");
    }

    private StaleIdentityException(String reason) {
      this.reason = reason;
    }
  }
}
