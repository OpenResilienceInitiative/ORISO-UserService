package de.caritas.cob.userservice.api.service.accountinvite;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.admin.service.admin.create.CreateAdminService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.IdentityAccountRemover;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import java.time.LocalDateTime;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts an AGENCY_ADMIN invite whose invitee does NOT also counsel (ORISO-Admin#1026, slice 3):
 * creates an agency-admin account — the same Keycloak roles and {@code admin}/{@code admin_agency}
 * rows the Admin panel's agency-admin form writes — and binds it to the invite's agency. No
 * consultant is created. The invitee who also counsels takes the consultant path of {@link
 * CounsellorInviteProvisioningService} instead.
 *
 * <p>Same lifecycle as the consultant path: the invite is marked {@code IN_PROGRESS}, the account
 * is created, the invite is claimed single-use, and any failure removes the Keycloak account again
 * and records {@code FAILED} so the resumable link can be retried.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgencyAdminInviteProvisioningService {

  private final @NonNull AccountInviteService accountInviteService;
  private final @NonNull AccountInviteRepository accountInviteRepository;
  private final @NonNull CreateAdminService createAdminService;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;
  private final @NonNull IdentityAccountRemover identityAccountRemover;

  @Transactional(noRollbackFor = RuntimeException.class)
  public AccountInvite acceptAsAgencyAdmin(String rawToken, String username, String password) {
    AccountInvite invite = accountInviteService.findInviteByToken(rawToken);
    if (invite.getTargetRole() != AccountInviteTargetRole.AGENCY_ADMIN) {
      throw new BadRequestException("Not an agency-admin invite");
    }
    if (invite.getStatus() != AccountInviteStatus.EMAIL_SENT
        || (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(LocalDateTime.now()))) {
      // Same resume/consumed contract as every other accept path.
      return accountInviteService.acceptInvite(rawToken, null);
    }
    if (invite.getProvisioningStatus() == AccountInviteProvisioningStatus.IN_PROGRESS) {
      throw new ConflictException("Account invite provisioning is already in progress");
    }
    if (invite.getTenantId() == null || invite.getAgencyId() == null) {
      throw new BadRequestException("Agency-admin invite requires tenant and agency");
    }
    if (isBlank(username) || isBlank(password)) {
      throw new BadRequestException("username and password are required");
    }

    invite.setProvisioningStatus(AccountInviteProvisioningStatus.IN_PROGRESS);
    invite.setProvisioningFailureReason(null);
    invite.setUpdateDate(LocalDateTime.now());
    accountInviteRepository.save(invite);

    String adminId = null;
    TenantData requestTenant = snapshotTenantContext();
    TenantContext.setCurrentTenant(invite.getTenantId());
    try {
      var admin =
          createAdminService.createNewAgencyAdminInTenant(toAdmin(invite, username, password));
      adminId = admin.getId();
      adminAgencyRepository.save(
          AdminAgency.builder()
              .admin(admin)
              .agencyId(invite.getAgencyId())
              .createDate(nowInUtc())
              .updateDate(nowInUtc())
              .build());

      AccountInvite accepted = accountInviteService.acceptInvite(rawToken, adminId);
      accepted.setProvisionedUserId(adminId);
      accepted.setProvisioningStatus(AccountInviteProvisioningStatus.COMPLETED);
      accepted.setProvisioningFailureReason(null);
      accepted.setAlsoCounsellor(false);
      accepted.setUpdateDate(LocalDateTime.now());
      log.info(
          "Agency-admin invite {} created an agency admin for agency {} (no consultant)",
          invite.getId(),
          invite.getAgencyId());
      return accountInviteRepository.save(accepted);
    } catch (RuntimeException failure) {
      if (adminId != null) {
        try {
          identityAccountRemover.rollbackUser(adminId);
        } catch (RuntimeException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
      }
      invite.setProvisionedUserId(null);
      invite.setProvisioningStatus(AccountInviteProvisioningStatus.FAILED);
      invite.setProvisioningFailureReason(truncate(failure.getMessage()));
      invite.setUpdateDate(LocalDateTime.now());
      accountInviteRepository.save(invite);
      throw failure;
    } finally {
      restoreTenantContext(requestTenant);
    }
  }

  private static CreateAdminDTO toAdmin(AccountInvite invite, String username, String password) {
    CreateAdminDTO dto = new CreateAdminDTO();
    dto.setUsername(username.trim());
    dto.setEmail(invite.getRecipientEmail().trim().toLowerCase(java.util.Locale.ROOT));
    dto.setFirstname(isBlank(invite.getFirstName()) ? "Admin" : invite.getFirstName());
    dto.setLastname(isBlank(invite.getLastName()) ? "Admin" : invite.getLastName());
    dto.setPassword(password);
    dto.setTenantId(invite.getTenantId().intValue());
    return dto;
  }

  private static TenantData snapshotTenantContext() {
    TenantData tenantData = TenantContext.getCurrentTenantData();
    return tenantData == null
        ? null
        : new TenantData(tenantData.getTenantId(), tenantData.getSubdomain());
  }

  private static void restoreTenantContext(TenantData tenantData) {
    if (tenantData == null) {
      TenantContext.clear();
    } else {
      TenantContext.setCurrentTenantData(tenantData);
    }
  }

  private static String truncate(String value) {
    return value == null || value.length() <= 1024 ? value : value.substring(0, 1024);
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
