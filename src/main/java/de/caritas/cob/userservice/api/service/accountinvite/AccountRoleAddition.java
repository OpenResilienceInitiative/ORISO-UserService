package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives an existing account one more role (ORISO-Admin#1026): "+ agency admin" for a counsellor.
 * Only adding; removing a role stays in the Users area. Same rule as inviting: the caller may only
 * give roles they could invite, to accounts within their scope.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountRoleAddition {

  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull AdminRepository adminRepository;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;
  private final @NonNull AdminScope adminScope;
  private final @NonNull AccountInviteAccessPolicy accessPolicy;
  private final @NonNull CounsellorAgencyAdminGrantService agencyAdminGrant;
  private final @NonNull AuthenticatedUser authenticatedUser;

  /** {@code agencyId} may be omitted when the counsellor counsels in exactly one agency. */
  public record AddRoleCommand(AccountInviteTargetRole role, Long agencyId) {}

  public record AddedRole(
      String consultantId, AccountInviteTargetRole role, List<Long> agencyIds) {}

  /**
   * @throws CustomValidationHttpStatusException 409 ROLE_ALREADY_GRANTED
   */
  @Transactional
  public AddedRole add(String consultantId, AddRoleCommand command) {
    if (command == null || command.role() == null) {
      throw new BadRequestException("role is required");
    }
    if (command.role() != AccountInviteTargetRole.AGENCY_ADMIN) {
      throw new BadRequestException(
          "Only AGENCY_ADMIN can be added to an account; other role changes are made in the"
              + " Users area");
    }
    accessPolicy.assertMayInvite(command.role());
    Consultant consultant =
        TenantContext.supplyAcrossTenants(() -> consultantRepository.findById(consultantId))
            .filter(found -> found.getDeleteDate() == null)
            .orElseThrow(() -> new NotFoundException("Consultant not found"));
    adminScope.assertMay(Target.counsellor(consultantId));
    Long agencyId = agencyOf(consultant, command.agencyId());
    Optional<Admin> admin =
        TenantContext.supplyAcrossTenants(() -> adminRepository.findById(consultantId));
    if (admin.isPresent()
        && (admin.get().getType() != Admin.AdminType.AGENCY
            || agencyIdsOfAdmin(consultantId).contains(agencyId))) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.ROLE_ALREADY_GRANTED, HttpStatus.CONFLICT);
    }
    agencyAdminGrant.grantAgencyAdmin(consultant, agencyId);
    log.info(
        "Admin {} made counsellor {} agency admin of agency {}",
        authenticatedUser.getUserId(),
        consultantId,
        agencyId);
    return new AddedRole(
        consultantId, command.role(), agencyIdsOfAdmin(consultantId).stream().sorted().toList());
  }

  private Long agencyOf(Consultant consultant, Long requested) {
    List<Long> counselsIn =
        TenantContext.supplyAcrossTenants(
                () ->
                    consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(
                        consultant.getId()))
            .stream()
            .map(ConsultantAgency::getAgencyId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (requested == null) {
      if (counselsIn.size() != 1) {
        throw new BadRequestException("agencyId is required");
      }
      return counselsIn.get(0);
    }
    if (!counselsIn.contains(requested)) {
      throw new BadRequestException(
          "agencyId " + requested + " is not an agency the counsellor counsels in");
    }
    return requested;
  }

  private List<Long> agencyIdsOfAdmin(String adminId) {
    return TenantContext.supplyAcrossTenants(() -> adminAgencyRepository.findByAdminId(adminId))
        .stream()
        .map(AdminAgency::getAgencyId)
        .filter(Objects::nonNull)
        .toList();
  }
}
