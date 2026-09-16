package de.caritas.cob.userservice.api.picture;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.*;

import de.caritas.cob.userservice.api.admin.facade.AdminUserFacade;
import de.caritas.cob.userservice.api.admin.service.agency.ConsultantAgencyAdminService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConsultantPictureAccess {
  private final AuthenticatedUser caller;
  private final ConsultantRepository consultants;
  private final AdminUserFacade admins;
  private final ConsultantAgencyAdminService agencies;

  @Value("${multitenancy.enabled:true}")
  private boolean multitenancy;

  public void check(String id, boolean write) {
    checkAuthority(write);
    checkTarget(
        consultants
            .findByIdAndDeleteDateIsNull(id)
            .orElseThrow(() -> new NotFoundException("Consultant not found")),
        write);
  }

  public void checkTarget(Consultant target, boolean write) {
    checkAuthority(write);
    if (target.getDeleteDate() != null) throw new NotFoundException("Consultant not found");
    // A platform administrator is the sole explicit cross-tenant exception. Technical tenant
    // context and caller-controlled headers alone never authorize private picture access.
    if (!caller.isPlatformAdmin()) {
      if (multitenancy && (caller.getTenantId() == null || caller.getTenantId() <= 0))
        throw new ForbiddenException("Picture access denied");
      if (!Objects.equals(caller.getTenantId(), target.getTenantId()))
        throw new ForbiddenException("Picture access denied");
    }
    // Permission mapping also accepts normalized and resource roles. Their restriction must
    // survive even when the legacy raw-realm helper cannot recognize that representation.
    boolean restricted =
        caller.hasRestrictedAgencyPriviliges()
            || (caller.getGrantedAuthorities().contains(RESTRICTED_AGENCY_ADMIN)
                && !caller.isAgencySuperAdmin());
    if (restricted
        && Collections.disjoint(
            admins.findAdminUserAgencyIds(caller.getUserId()),
            agencies.findConsultantAgencyIds(target.getId())))
      throw new ForbiddenException("Picture access denied");
  }

  private void checkAuthority(boolean write) {
    var allowed =
        write
            ? Set.of(CONSULTANT_UPDATE, TECHNICAL_DEFAULT)
            : Set.of(
                CONSULTANT_DEFAULT,
                USER_ADMIN,
                CONSULTANT_UPDATE,
                TENANT_ADMIN,
                SINGLE_TENANT_ADMIN,
                RESTRICTED_AGENCY_ADMIN,
                TECHNICAL_DEFAULT);
    if (caller.getGrantedAuthorities() == null
        || Collections.disjoint(allowed, caller.getGrantedAuthorities()))
      throw new ForbiddenException("Picture access denied");
  }
}
