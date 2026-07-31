package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves how far an admin may read the session-bound audit logs (supervision, case handover).
 *
 * <p>Tenant-wide admins (platform admin, Träger admin) keep the tenant scope they always had.
 * Beratungsstellen-Admins — {@code restricted-agency-admin}, which is always paired with {@code
 * user-admin} and therefore already passes the endpoint's authority check — are narrowed to the
 * agencies they are actually assigned to, so surfacing these logs in the Admin menu
 * (ORISO-Admin#84) cannot turn into a cross-agency read.
 *
 * <p>Fail closed: an assigned-to-nothing agency admin gets an empty scope, never the tenant.
 */
@Component
@RequiredArgsConstructor
public class AdminAuditAgencyScope {

  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;

  /**
   * @return the agency ids the current admin may read, or {@link Optional#empty()} when the admin
   *     reads the whole tenant and must not be narrowed.
   */
  public Optional<Set<Long>> resolveAgencyIds() {
    if (authenticatedUser.isTenantSuperAdmin() || authenticatedUser.isSingleTenantAdmin()) {
      return Optional.empty();
    }
    if (!authenticatedUser.hasRestrictedAgencyPriviliges()) {
      return Optional.empty();
    }
    return Optional.of(
        adminAgencyRepository.findByAdminId(authenticatedUser.getUserId()).stream()
            .map(AdminAgency::getAgencyId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet()));
  }
}
