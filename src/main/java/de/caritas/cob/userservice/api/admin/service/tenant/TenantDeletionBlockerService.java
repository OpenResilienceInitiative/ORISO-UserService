package de.caritas.cob.userservice.api.admin.service.tenant;

import de.caritas.cob.userservice.api.adapters.web.dto.TenantDeletionBlockersDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyAdminService;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What still belongs to a Träger and therefore blocks its deletion (ORISO-UserService#1466).
 *
 * <p>TenantService used to delete a Träger whatever it still had: its agencies, counsellors and
 * advice seekers were left pointing at a Träger that no longer existed ("Unknown Tenant"), and the
 * admin interface could no longer delete them. TenantService now asks here first and refuses the
 * deletion while anything is left. Accounts already marked for deletion and soft-deleted agencies
 * do not block: the nightly job removes them. The technical group-chat-system users do not block
 * either; they go with their Träger.
 */
@Service
@RequiredArgsConstructor
public class TenantDeletionBlockerService {

  /** An invitation that can still be accepted would create an account in the deleted Träger. */
  static final List<AccountInviteStatus> OPEN_INVITE_STATUSES =
      List.of(
          AccountInviteStatus.WAITING_FOR_UNIT,
          AccountInviteStatus.DRAFT,
          AccountInviteStatus.EMAIL_SENT);

  private final @NonNull AdminScope adminScope;
  private final @NonNull AgencyAdminService agencyAdminService;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull UserRepository userRepository;
  private final @NonNull AdminRepository adminRepository;
  private final @NonNull AccountInviteRepository accountInviteRepository;
  private final @NonNull Clock clock;

  /**
   * @param tenantId the Träger to be deleted
   * @return how many of each kind still belong to the Träger
   * @throws de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException when the
   *     Träger is outside the caller's reach
   */
  @Transactional(readOnly = true)
  public TenantDeletionBlockersDTO findBlockers(Long tenantId) {
    adminScope.assertMay(Target.tenant(tenantId));

    var blockers =
        new TenantDeletionBlockersDTO()
            .agencies(agencyAdminService.countActiveAgenciesOfTenant(tenantId))
            .consultants(consultantRepository.countByTenantIdAndDeleteDateIsNull(tenantId))
            .adviceSeekers(userRepository.countActiveAdviceSeekersOfTenant(tenantId))
            .agencyAdmins(adminRepository.countByTenantIdAndType(tenantId, AdminType.AGENCY))
            .tenantAdmins(adminRepository.countByTenantIdAndType(tenantId, AdminType.TENANT))
            .openInvites(
                accountInviteRepository.countOpenInvitesOfTenant(
                    tenantId, OPEN_INVITE_STATUSES, LocalDateTime.now(clock)));

    return blockers.deletable(
        blockers.getAgencies()
                + blockers.getConsultants()
                + blockers.getAdviceSeekers()
                + blockers.getAgencyAdmins()
                + blockers.getTenantAdmins()
                + blockers.getOpenInvites()
            == 0);
  }
}
