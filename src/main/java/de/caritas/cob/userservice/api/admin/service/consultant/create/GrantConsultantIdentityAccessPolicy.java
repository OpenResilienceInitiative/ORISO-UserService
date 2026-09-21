package de.caritas.cob.userservice.api.admin.service.consultant.create;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Who may grant which admin a consultant identity ("cross-Träger" isolation of {@code POST
 * /useradmin/admins/{adminId}/grant-consultant-identity}).
 *
 * <p>The HTTP layer only checks {@code user-admin}, which every Träger admin and every
 * Beratungsstellen admin holds, and the admin is loaded by primary key, which the Hibernate tenant
 * filter does not narrow. Without this policy any admin could turn any other Träger's admin into a
 * counsellor and attach them to any agency.
 *
 * <ul>
 *   <li><b>Platform admin</b> (tenant {@code 0}) and callers without a tenant (single-tenant
 *       deployment): unrestricted — the same boundary as {@code AdminTenantOwnershipValidator} and
 *       {@code AccountInviteAccessPolicy}.
 *   <li><b>Träger admin</b> (bound to a tenant): only admins of their own tenant (the agencies are
 *       then bound to that tenant by {@code ConsultantTopicAgencyCompatibilityValidator}).
 *   <li><b>Beratungsstellen admin</b> (restricted agency admin): only admins sharing one of their
 *       own agencies (themselves included), and only into their own agencies.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrantConsultantIdentityAccessPolicy {

  static final String OUT_OF_SCOPE_MESSAGE =
      "Granting this consultant identity is outside the caller's scope";

  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;

  /**
   * Checks that the caller may grant {@code target} a consultant identity in {@code agencyIds}.
   *
   * @throws ForbiddenException if the target admin or one of the agencies lies outside the caller's
   *     scope
   */
  public void authorizeGrant(Admin target, Collection<Long> agencyIds) {
    List<Long> requestedAgencyIds =
        agencyIds == null ? List.of() : agencyIds.stream().filter(Objects::nonNull).toList();
    Long callerTenantId = boundTenantId();

    if (authenticatedUser.hasRestrictedAgencyPriviliges()) {
      authorizeAgencyAdmin(target, requestedAgencyIds, callerTenantId);
      return;
    }
    if (authenticatedUser.isPlatformAdmin() || callerTenantId == null) {
      return;
    }
    // Agencies need no check here: ConsultantTopicAgencyCompatibilityValidator already refuses
    // agencies outside the target admin's tenant, which this check pins to the caller's tenant.
    if (!callerTenantId.equals(target.getTenantId())) {
      throw deny("grant a consultant identity to admin " + target.getId() + " of another tenant");
    }
  }

  private void authorizeAgencyAdmin(
      Admin target, List<Long> requestedAgencyIds, Long callerTenantId) {
    if (callerTenantId != null && !callerTenantId.equals(target.getTenantId())) {
      throw deny("grant a consultant identity to admin " + target.getId() + " of another tenant");
    }
    Set<Long> callerAgencyIds = agencyIdsOf(authenticatedUser.getUserId());
    if (Collections.disjoint(callerAgencyIds, agencyIdsOf(target.getId()))) {
      throw deny(
          "grant a consultant identity to admin " + target.getId() + " outside own agencies");
    }
    if (!callerAgencyIds.containsAll(requestedAgencyIds)) {
      throw deny("assign a consultant identity to agencies " + requestedAgencyIds);
    }
  }

  private Set<Long> agencyIdsOf(String adminId) {
    return adminAgencyRepository.findByAdminId(adminId).stream()
        .map(AdminAgency::getAgencyId)
        .filter(Objects::nonNull)
        .collect(Collectors.toUnmodifiableSet());
  }

  /** The caller's own tenant, or {@code null} for the platform (0) and single-tenant contexts. */
  private Long boundTenantId() {
    Long tenantId = authenticatedUser.getTenantId();
    if (tenantId == null || TenantContext.TECHNICAL_TENANT_ID.equals(tenantId)) {
      return null;
    }
    return tenantId;
  }

  private ForbiddenException deny(String attempt) {
    log.warn(
        "Admin {} (tenant {}) may not {}",
        authenticatedUser.getUserId(),
        authenticatedUser.getTenantId(),
        attempt);
    return new ForbiddenException(OUT_OF_SCOPE_MESSAGE);
  }
}
