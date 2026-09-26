package de.caritas.cob.userservice.api.admin.service.admin;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The calling admin's reach over other admins, users and agencies on the {@code /useradmin/**}
 * endpoints that take an ID from the path or body ("cross-Träger" isolation).
 *
 * <p>Those routes mostly only require {@code user-admin}, which every Träger admin and every
 * Beratungsstellen admin holds, and a lookup by primary key is not narrowed by the Hibernate tenant
 * filter (neither is {@code admin_agency}, which carries no tenant). So the scope has to be checked
 * explicitly:
 *
 * <ul>
 *   <li><b>Platform admin</b> (tenant {@code 0} with the platform-admin roles) and callers without
 *       a tenant (single-tenant deployment): unrestricted — the same boundary as {@link
 *       AdminTenantOwnershipValidator} and {@code AccountInviteAccessPolicy}.
 *   <li><b>Träger admin</b> (bound to a tenant): admins and users of their own tenant, agencies of
 *       their own tenant.
 *   <li><b>Beratungsstellen admin</b> (restricted agency admin): admins and counsellors sharing one
 *       of their own agencies (themselves included), advice seekers with a session in, or a
 *       registration for, one of their own agencies, and only their own agencies.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminCallerScope {

  static final String OUT_OF_SCOPE_MESSAGE = "The target is outside the caller's scope";

  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull AdminRepository adminRepository;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull AgencyService agencyService;
  private final @NonNull UserRepository userRepository;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull UserAgencyRepository userAgencyRepository;

  /**
   * Checks that the caller may act on the admin with the given ID. An unknown ID passes, so the
   * endpoint answers it the way it always has.
   *
   * @throws ForbiddenException if the admin lies outside the caller's scope
   */
  public void assertMayActOnAdmin(String adminId) {
    adminRepository.findById(adminId).ifPresent(this::assertMayActOnAdmin);
  }

  /**
   * Checks that the caller may act on {@code target}.
   *
   * @throws ForbiddenException if the admin lies outside the caller's scope
   */
  public void assertMayActOnAdmin(Admin target) {
    if (isUnrestricted()) {
      return;
    }
    if (!isOwnTenant(target.getTenantId())) {
      throw deny("act on admin " + target.getId() + " of tenant " + target.getTenantId());
    }
    if (authenticatedUser.hasRestrictedAgencyPriviliges()
        && Collections.disjoint(ownAgencyIds(), agencyIdsOfAdmin(target.getId()))) {
      throw deny("act on admin " + target.getId() + " outside own agencies");
    }
  }

  /**
   * Checks that the caller may read the identities of the user with the given ID — an admin or a
   * counsellor. A user that is neither (an advice seeker, an unknown ID) is only in the scope of an
   * unrestricted caller: nothing proves which tenant or agency it belongs to.
   *
   * @throws ForbiddenException if the user lies outside the caller's scope
   */
  public void assertMayReadUser(String userId) {
    if (isUnrestricted()) {
      return;
    }
    Optional<Admin> admin = adminRepository.findById(userId);
    if (admin.isPresent() && isInScope(admin.get().getTenantId(), agencyIdsOfAdmin(userId))) {
      return;
    }
    Optional<Consultant> consultant = consultantRepository.findById(userId);
    if (consultant.isPresent()
        && isInScope(consultant.get().getTenantId(), agencyIdsOfConsultant(userId))) {
      return;
    }
    throw deny("read the identities of user " + userId);
  }

  /**
   * Checks that the caller may assign, or take away, the given agencies.
   *
   * @throws ForbiddenException if one of the agencies lies outside the caller's scope
   */
  public void assertMayUseAgencies(Collection<Long> agencyIds) {
    if (agencyIds == null || agencyIds.isEmpty() || isUnrestricted()) {
      return;
    }
    Set<Long> requested =
        agencyIds.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    if (authenticatedUser.hasRestrictedAgencyPriviliges()) {
      if (!ownAgencyIds().containsAll(requested)) {
        throw deny("use agencies " + requested + " outside own agencies");
      }
      return;
    }
    // An agency missing from the response counts as foreign, so the check fails closed.
    Set<Long> ownTenantAgencyIds =
        agencyService.getAgenciesWithoutCaching(List.copyOf(requested)).stream()
            .filter(agency -> isOwnTenant(agency.getTenantId()))
            .map(AgencyDTO::getId)
            .collect(Collectors.toSet());
    if (!ownTenantAgencyIds.containsAll(requested)) {
      throw deny("use agencies " + requested + " outside own tenant");
    }
  }

  /**
   * Checks that the caller may read or change the counsellor with the given ID. A counsellor marked
   * for deletion counts with the agencies it had, so its deletion can still be paused by the admins
   * of those agencies. An unknown ID passes, so the endpoint answers it the way it always has.
   *
   * @throws ForbiddenException if the counsellor lies outside the caller's scope
   */
  public void assertMayActOnConsultant(String consultantId) {
    if (isUnrestricted()) {
      return;
    }
    consultantRepository
        .findById(consultantId)
        .ifPresent(
            consultant -> {
              if (!isInScope(consultant.getTenantId(), agencyIdsOfConsultant(consultant))) {
                throw deny("act on consultant " + consultantId);
              }
            });
  }

  /**
   * Checks that the caller may read or change the advice seeker with the given ID. Their agencies
   * are the agencies of their sessions and of their agency registrations. An unknown ID passes, so
   * the endpoint answers it the way it always has.
   *
   * @throws ForbiddenException if the advice seeker lies outside the caller's scope
   */
  public void assertMayActOnAsker(String askerId) {
    if (isUnrestricted()) {
      return;
    }
    userRepository
        .findById(askerId)
        .ifPresent(
            asker -> {
              if (!isOwnTenant(asker.getTenantId())) {
                throw deny("act on asker " + askerId + " of tenant " + asker.getTenantId());
              }
              if (authenticatedUser.hasRestrictedAgencyPriviliges()
                  && Collections.disjoint(ownAgencyIds(), agencyIdsOfAsker(asker))) {
                throw deny("act on asker " + askerId + " outside own agencies");
              }
            });
  }

  private boolean isInScope(Long tenantId, Set<Long> agencyIds) {
    if (!isOwnTenant(tenantId)) {
      return false;
    }
    return !authenticatedUser.hasRestrictedAgencyPriviliges()
        || !Collections.disjoint(ownAgencyIds(), agencyIds);
  }

  /**
   * Platform admins, technical users, and tenant-less callers (single-tenant deployment) other than
   * Beratungsstellen admins are unrestricted.
   *
   * @throws ForbiddenException for tenant 0 without the platform-admin roles
   */
  private boolean isUnrestricted() {
    boolean restricted = authenticatedUser.hasRestrictedAgencyPriviliges();
    if (!restricted && (authenticatedUser.isPlatformAdmin() || isTechnicalUser())) {
      return true;
    }
    // Tenant 0 is nobody's Träger: without the platform-admin roles it grants no reach at all.
    if (TenantContext.TECHNICAL_TENANT_ID.equals(authenticatedUser.getTenantId())) {
      throw deny("act from tenant 0 without the platform-admin roles");
    }
    return !restricted && authenticatedUser.getTenantId() == null;
  }

  private boolean isTechnicalUser() {
    var roles = authenticatedUser.getRoles();
    return roles != null && roles.contains(UserRole.TECHNICAL.getValue());
  }

  /** A Beratungsstellen admin without a bound tenant is only narrowed by their agencies. */
  private boolean isOwnTenant(Long tenantId) {
    Long callerTenantId = authenticatedUser.getTenantId();
    return callerTenantId == null || callerTenantId.equals(tenantId);
  }

  private Set<Long> ownAgencyIds() {
    return agencyIdsOfAdmin(authenticatedUser.getUserId());
  }

  private Set<Long> agencyIdsOfAdmin(String adminId) {
    return adminAgencyRepository.findByAdminId(adminId).stream()
        .map(AdminAgency::getAgencyId)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(HashSet::new));
  }

  private Set<Long> agencyIdsOfConsultant(String consultantId) {
    return consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(consultantId).stream()
        .map(ConsultantAgency::getAgencyId)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(HashSet::new));
  }

  /**
   * A deleted counsellor counts with the relations its deletion removed, which carry its own delete
   * date; agencies it had left before stay out of reach.
   */
  private Set<Long> agencyIdsOfConsultant(Consultant consultant) {
    if (consultant.getDeleteDate() == null) {
      return agencyIdsOfConsultant(consultant.getId());
    }
    return consultantAgencyRepository.findByConsultantId(consultant.getId()).stream()
        .filter(relation -> consultant.getDeleteDate().equals(relation.getDeleteDate()))
        .map(ConsultantAgency::getAgencyId)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(HashSet::new));
  }

  private Set<Long> agencyIdsOfAsker(User asker) {
    return Stream.concat(
            sessionRepository.findByUserUserId(asker.getUserId()).stream()
                .map(Session::getAgencyId),
            userAgencyRepository.findByUser(asker).stream().map(UserAgency::getAgencyId))
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(HashSet::new));
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
