package de.caritas.cob.userservice.api.admin.service.admin;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSupportAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSupportAdminDTO.ProvisioningStatusEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSupportAdminDTO.SecondFactorStatusEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.GlobalSupportAdminSearchResultDTO;
import de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService;
import de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.SupportAdminProfile;
import de.caritas.cob.userservice.api.model.SupportAdminProfile.SupportAdminStatus;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.SupportAccessRevoker;
import de.caritas.cob.userservice.api.port.out.SupportAdminProfileRepository;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifecycle of the Global Support Admin identity (ADR-018 §2). Creation is fail-closed: the
 * Keycloak account exists disabled and unprivileged until provisioning succeeded. {@code
 * support_admin_profile} — not the bearer token — decides whether a GSA may act, so disabling takes
 * effect immediately even for a token that was issued a minute ago.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GlobalSupportAdminUserService {

  private static final String REASON_ADMIN_DISABLED = "SUPPORT_ADMIN_DISABLED";

  private final @NonNull RetrieveAdminService retrieveAdminService;
  private final @NonNull CreateAdminService createAdminService;
  private final @NonNull IdentityClient identityClient;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull SupportAdminProfileRepository supportAdminProfileRepository;
  private final @NonNull SupportAccessRevoker supportAccessRevoker;

  @Transactional
  public GlobalSupportAdminDTO create(CreateAdminDTO request) {
    requirePlatformAdmin();
    var admin = createAdminService.createNewGlobalSupportAdmin(request);
    var now = nowInUtc();
    var profile =
        supportAdminProfileRepository.save(
            SupportAdminProfile.builder()
                .adminId(admin.getId())
                .status(SupportAdminStatus.INVITED)
                .createDate(now)
                .updateDate(now)
                .build());
    return map(admin, provision(admin, profile));
  }

  // Not read-only: listing reconciles PENDING_2FA into ACTIVE once Keycloak confirms enrolment.
  @Transactional
  public GlobalSupportAdminSearchResultDTO search(String infix, PageRequest pageRequest) {
    requirePlatformAdmin();
    var page = retrieveAdminService.findAllByInfix(infix, Admin.AdminType.SUPPORT, pageRequest);
    var ids = page.stream().map(Admin.AdminBase::getId).collect(Collectors.toSet());
    Map<String, Admin> adminsById =
        retrieveAdminService.findAllById(ids).stream()
            .collect(Collectors.toMap(Admin::getId, Function.identity()));
    Map<String, SupportAdminProfile> profilesById =
        supportAdminProfileRepository.findAllByAdminIdIn(ids).stream()
            .collect(Collectors.toMap(SupportAdminProfile::getAdminId, Function.identity()));

    var items =
        page.stream()
            .map(
                adminBase -> {
                  var admin = adminsById.get(adminBase.getId());
                  if (admin == null) {
                    throw new IllegalStateException(
                        "Global Support Admin projection has no matching entity");
                  }
                  return map(admin, profilesById.get(admin.getId()));
                })
            .toList();

    return new GlobalSupportAdminSearchResultDTO(items, Math.toIntExact(page.getTotalElements()));
  }

  /**
   * Blocks the account and withdraws access in the order that keeps the security state truthful:
   * new handshakes are refused first, running sessions are marked for revocation, and only then are
   * the Keycloak role and sign-in taken away.
   */
  @Transactional
  public GlobalSupportAdminDTO disable(String adminId) {
    requirePlatformAdmin();
    var admin = retrieveAdminService.findAdmin(adminId, Admin.AdminType.SUPPORT);
    var profile = requireProfile(adminId);

    profile.setStatus(SupportAdminStatus.DISABLING);
    profile.setUpdateDate(nowInUtc());
    supportAdminProfileRepository.saveAndFlush(profile);

    var revoked = supportAccessRevoker.revokeAllForSupportAdmin(adminId, REASON_ADMIN_DISABLED);
    log.info("Disabling Global Support Admin {} revoked {} support session(s)", adminId, revoked);

    identityClient.removeRoleIfPresent(adminId, UserRole.GLOBAL_SUPPORT_ADMIN.getValue());
    identityClient.setUserEnabled(adminId, false);

    profile.setStatus(SupportAdminStatus.DISABLED);
    profile.setDisabledDate(nowInUtc());
    profile.setUpdateDate(nowInUtc());
    profile.setLastError(null);
    return map(admin, supportAdminProfileRepository.save(profile));
  }

  /** Restarts onboarding. The account stays unusable until a second factor is enrolled. */
  @Transactional
  public GlobalSupportAdminDTO enable(String adminId) {
    requirePlatformAdmin();
    var admin = retrieveAdminService.findAdmin(adminId, Admin.AdminType.SUPPORT);
    var profile = requireProfile(adminId);

    profile.setStatus(SupportAdminStatus.INVITED);
    profile.setDisabledDate(null);
    profile.setProvisioningAttempts(0);
    profile.setLastError(null);
    profile.setUpdateDate(nowInUtc());
    return map(admin, provision(admin, supportAdminProfileRepository.saveAndFlush(profile)));
  }

  /**
   * Gate for every operation a GSA may perform. Checks the realm role, the authoritative profile
   * state, and the live second factor — a Keycloak lookup failure fails closed rather than passing.
   */
  @Transactional
  public void requireOperationalSupportAdmin() {
    if (!authenticatedUser.isGlobalSupportAdmin()) {
      throw new AccessDeniedException("Global Support Admin role is required");
    }
    var adminId = authenticatedUser.getUserId();
    var profile =
        supportAdminProfileRepository
            .findById(adminId)
            .orElseThrow(
                () ->
                    new SupportAdminNotOperationalException(
                        "Global Support Admin %s has no profile".formatted(adminId)));
    if (profile.getStatus().isTerminalBlock()
        || profile.getStatus() == SupportAdminStatus.DISABLING) {
      throw new SupportAdminNotOperationalException(
          "Global Support Admin %s is %s".formatted(adminId, profile.getStatus()));
    }
    // Enrolling the second factor is what promotes the account; that happens without a Platform
    // Admin looking at the list, so the promotion has to be resolvable here too.
    var admin = retrieveAdminService.findAdmin(adminId, Admin.AdminType.SUPPORT);
    var reconciled = reconcile(profile, secondFactorStatus(admin));
    if (reconciled == null || !reconciled.getStatus().isOperational()) {
      throw new SupportAdminNotOperationalException(
          "An active second factor is required for Global Support Admin operations");
    }
  }

  /**
   * Assigns the privileged role and releases the account. A failure leaves PROVISIONING_FAILED and
   * a still-disabled Keycloak user, which is the only safe outcome.
   */
  private SupportAdminProfile provision(Admin admin, SupportAdminProfile profile) {
    profile.setProvisioningAttempts(profile.getProvisioningAttempts() + 1);
    try {
      identityClient.updateRole(admin.getId(), UserRole.GLOBAL_SUPPORT_ADMIN);
      identityClient.setUserEnabled(admin.getId(), true);
      profile.setStatus(SupportAdminStatus.PENDING_2FA);
      profile.setLastError(null);
    } catch (RuntimeException e) {
      log.error("Provisioning of Global Support Admin {} failed", admin.getId(), e);
      safelyDisable(admin.getId());
      profile.setStatus(SupportAdminStatus.PROVISIONING_FAILED);
      profile.setLastError(StringUtils.abbreviate(String.valueOf(e.getMessage()), 1000));
    }
    profile.setUpdateDate(nowInUtc());
    return supportAdminProfileRepository.save(profile);
  }

  private void safelyDisable(String adminId) {
    try {
      identityClient.setUserEnabled(adminId, false);
    } catch (RuntimeException e) {
      log.error(
          "Could not keep failed Global Support Admin {} disabled; it must be blocked manually",
          adminId,
          e);
    }
  }

  private SupportAdminProfile requireProfile(String adminId) {
    return supportAdminProfileRepository
        .findById(adminId)
        .orElseGet(
            () -> {
              // An account created before this table existed is treated as freshly invited rather
              // than silently operational.
              var now = nowInUtc();
              return supportAdminProfileRepository.save(
                  SupportAdminProfile.builder()
                      .adminId(adminId)
                      .status(SupportAdminStatus.INVITED)
                      .createDate(now)
                      .updateDate(now)
                      .build());
            });
  }

  private SecondFactorStatusEnum secondFactorStatus(Admin admin) {
    try {
      var otpInfo = identityClient.getOtpCredential(admin.getUsername());
      return otpInfo != null && Boolean.TRUE.equals(otpInfo.getOtpSetup())
          ? SecondFactorStatusEnum.ACTIVE
          : SecondFactorStatusEnum.PENDING_2_FA;
    } catch (RuntimeException exception) {
      log.warn(
          "Could not resolve second-factor status for Global Support Admin {}",
          admin.getId(),
          exception);
      return SecondFactorStatusEnum.UNAVAILABLE;
    }
  }

  /**
   * PENDING_2FA becomes ACTIVE the moment Keycloak confirms an enrolled second factor. The
   * promotion is persisted so authorization does not depend on a Keycloak round trip being
   * available.
   */
  private SupportAdminProfile reconcile(
      SupportAdminProfile profile, SecondFactorStatusEnum secondFactor) {
    if (profile == null || profile.getStatus().isTerminalBlock()) {
      return profile;
    }
    var target =
        secondFactor == SecondFactorStatusEnum.ACTIVE
            ? SupportAdminStatus.ACTIVE
            : SupportAdminStatus.PENDING_2FA;
    if (profile.getStatus() == SupportAdminStatus.DISABLING
        || profile.getStatus() == SupportAdminStatus.INVITED
        || profile.getStatus() == target) {
      return profile;
    }
    profile.setStatus(target);
    profile.setUpdateDate(nowInUtc());
    return supportAdminProfileRepository.save(profile);
  }

  private void requirePlatformAdmin() {
    if (!authenticatedUser.isPlatformAdmin()) {
      throw new AccessDeniedException(
          "Only Platform Admins may manage Global Support Admin accounts");
    }
  }

  private GlobalSupportAdminDTO map(Admin admin, SupportAdminProfile profile) {
    var secondFactor = secondFactorStatus(admin);
    var reconciled = reconcile(profile, secondFactor);
    return new GlobalSupportAdminDTO(
            admin.getId(),
            admin.getUsername(),
            admin.getFirstName(),
            admin.getLastName(),
            admin.getEmail(),
            secondFactor,
            provisioningStatus(reconciled))
        .createDate(admin.getCreateDate() == null ? null : admin.getCreateDate().toString())
        .updateDate(admin.getUpdateDate() == null ? null : admin.getUpdateDate().toString());
  }

  private ProvisioningStatusEnum provisioningStatus(SupportAdminProfile profile) {
    return profile == null
        ? ProvisioningStatusEnum.INVITED
        : ProvisioningStatusEnum.fromValue(profile.getStatus().name());
  }

  /** Maps to 423 LOCKED: the caller is a GSA, but the account is blocked or not yet set up. */
  public static class SupportAdminNotOperationalException extends RuntimeException {
    public SupportAdminNotOperationalException(String message) {
      super(message);
    }
  }
}
