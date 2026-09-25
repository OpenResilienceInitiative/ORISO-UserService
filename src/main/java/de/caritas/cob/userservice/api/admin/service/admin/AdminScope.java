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
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * How far the calling admin reaches: the whole platform, one Träger, or some agencies of one
 * Träger. The one place that answers "may this admin see or change that?".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminScope {

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

  @Value("${multitenancy.enabled:false}")
  private boolean multitenancyEnabled;

  public sealed interface Reach permits Platform, Tenant, Agencies {
    /** The caller's Träger; {@code null} for the platform and on single-tenant deployments. */
    Long tenantId();
  }

  public record Platform() implements Reach {
    @Override
    public Long tenantId() {
      return null;
    }
  }

  public record Tenant(Long tenantId) implements Reach {}

  public record Agencies(Long tenantId, Set<Long> ids) implements Reach {}

  /** Something an admin acts on. An unknown admin, counsellor or advice seeker passes. */
  public sealed interface Target {
    static Target admin(String id) {
      return new AdminTarget(id);
    }

    static Target counsellor(String id) {
      return new CounsellorTarget(id);
    }

    static Target adviceSeeker(String id) {
      return new AdviceSeekerTarget(id);
    }

    /** An admin or counsellor whose identities are read; an unknown user is refused. */
    static Target account(String id) {
      return new AccountTarget(id);
    }

    static Target agencies(Collection<Long> ids) {
      return new AgenciesTarget(ids);
    }

    /** A Träger as a whole, e.g. to create or list its Träger admins. */
    static Target tenant(Long id) {
      return new TenantTarget(id);
    }

    /** A row placed in a Träger and possibly one agency, e.g. an account invite. */
    static Target placedIn(Long tenantId, Long agencyId) {
      return new PlacedTarget(tenantId, agencyId);
    }
  }

  private record AdminTarget(String id) implements Target {}

  private record CounsellorTarget(String id) implements Target {}

  private record AdviceSeekerTarget(String id) implements Target {}

  private record AccountTarget(String id) implements Target {}

  private record AgenciesTarget(Collection<Long> ids) implements Target {}

  private record TenantTarget(Long id) implements Target {}

  private record PlacedTarget(Long tenantId, Long agencyId) implements Target {}

  /** Which rows of {@code T} belong to one of the given agencies. */
  @FunctionalInterface
  public interface InAgencies<T> {
    Predicate of(Root<T> root, CriteriaQuery<?> query, CriteriaBuilder cb, Set<Long> agencyIds);
  }

  /**
   * @throws ForbiddenException if the caller has no tenant, or tenant 0 without being a platform
   *     admin, on a multi-tenant deployment
   */
  public Reach current() {
    boolean agencyAdmin = authenticatedUser.hasRestrictedAgencyPriviliges();
    if (!multitenancyEnabled) {
      return agencyAdmin ? new Agencies(null, ownAgencyIds()) : new Platform();
    }
    if (!agencyAdmin && (authenticatedUser.isPlatformAdmin() || isTechnicalUser())) {
      return new Platform();
    }
    Long tenantId = authenticatedUser.getTenantId();
    if (tenantId == null || TenantContext.TECHNICAL_TENANT_ID.equals(tenantId)) {
      throw deny("act without a tenant of their own");
    }
    return agencyAdmin ? new Agencies(tenantId, ownAgencyIds()) : new Tenant(tenantId);
  }

  /**
   * @throws ForbiddenException if {@code target} lies outside the caller's reach
   */
  public void assertMay(Target target) {
    Reach reach = current();
    if (reach instanceof Platform) {
      return;
    }
    boolean allowed =
        switch (target) {
          case AdminTarget admin -> mayActOnAdmin(reach, admin.id());
          case CounsellorTarget counsellor -> mayActOnCounsellor(reach, counsellor.id());
          case AdviceSeekerTarget asker -> mayActOnAdviceSeeker(reach, asker.id());
          case AccountTarget account -> mayReadAccount(reach, account.id());
          case AgenciesTarget agencies -> mayUseAgencies(reach, agencies.ids());
          case TenantTarget tenant ->
              tenant.id() == null
                  || (!(reach instanceof Agencies) && isTenantReach(reach, tenant.id()));
          case PlacedTarget placed -> mayActOnPlaced(reach, placed);
        };
    if (!allowed) {
      throw deny("act on " + target);
    }
  }

  /** Limits a list query to the caller's agencies; tenants are kept apart by the tenant filter. */
  public <T> Specification<T> narrow(Specification<T> specification, InAgencies<T> inAgencies) {
    if (!(current() instanceof Agencies agencies)) {
      return specification;
    }
    Specification<T> own =
        (root, query, cb) ->
            agencies.ids().isEmpty()
                ? cb.disjunction()
                : inAgencies.of(root, query, cb, agencies.ids());
    return Specification.where(specification).and(own);
  }

  private boolean mayActOnAdmin(Reach reach, String adminId) {
    return TenantContext.supplyAcrossTenants(() -> adminRepository.findById(adminId))
        .map(admin -> isInReach(reach, admin.getTenantId(), agencyIdsOfAdmin(admin.getId())))
        .orElse(true);
  }

  private boolean mayActOnCounsellor(Reach reach, String consultantId) {
    return TenantContext.supplyAcrossTenants(() -> consultantRepository.findById(consultantId))
        .map(consultant -> isInReach(reach, consultant.getTenantId(), agencyIdsOf(consultant)))
        .orElse(true);
  }

  private boolean mayActOnAdviceSeeker(Reach reach, String askerId) {
    return TenantContext.supplyAcrossTenants(() -> userRepository.findById(askerId))
        .map(asker -> isInReach(reach, asker.getTenantId(), agencyIdsOfAsker(asker)))
        .orElse(true);
  }

  private boolean mayReadAccount(Reach reach, String userId) {
    Optional<Admin> admin =
        TenantContext.supplyAcrossTenants(() -> adminRepository.findById(userId));
    if (admin.isPresent()
        && isInReach(reach, admin.get().getTenantId(), agencyIdsOfAdmin(userId))) {
      return true;
    }
    return TenantContext.supplyAcrossTenants(() -> consultantRepository.findById(userId))
        .map(consultant -> isInReach(reach, consultant.getTenantId(), agencyIdsOf(consultant)))
        .orElse(false);
  }

  private boolean mayUseAgencies(Reach reach, Collection<Long> agencyIds) {
    if (agencyIds == null || agencyIds.isEmpty()) {
      return true;
    }
    Set<Long> requested =
        agencyIds.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    if (reach instanceof Agencies agencies) {
      return agencies.ids().containsAll(requested);
    }
    // One lookup for all; an agency missing from the answer counts as foreign (fail closed).
    return agencyService.getAgenciesWithoutCaching(List.copyOf(requested)).stream()
        .filter(agency -> isTenantReach(reach, agency.getTenantId()))
        .map(AgencyDTO::getId)
        .collect(Collectors.toSet())
        .containsAll(requested);
  }

  private boolean mayActOnPlaced(Reach reach, PlacedTarget placed) {
    if (!isTenantReach(reach, placed.tenantId())) {
      return false;
    }
    return !(reach instanceof Agencies agencies)
        || (placed.agencyId() != null && agencies.ids().contains(placed.agencyId()));
  }

  private boolean isInReach(Reach reach, Long tenantId, Set<Long> agencyIds) {
    if (!isTenantReach(reach, tenantId)) {
      return false;
    }
    return !(reach instanceof Agencies agencies)
        || !Collections.disjoint(agencies.ids(), agencyIds);
  }

  /** An agency admin on a single-tenant deployment has no tenant to compare. */
  private static boolean isTenantReach(Reach reach, Long tenantId) {
    return reach.tenantId() == null ? reach instanceof Agencies : reach.tenantId().equals(tenantId);
  }

  private boolean isTechnicalUser() {
    var roles = authenticatedUser.getRoles();
    return roles != null && roles.contains(UserRole.TECHNICAL.getValue());
  }

  private Set<Long> ownAgencyIds() {
    return Collections.unmodifiableSet(agencyIdsOfAdmin(authenticatedUser.getUserId()));
  }

  private Set<Long> agencyIdsOfAdmin(String adminId) {
    return adminAgencyRepository.findByAdminId(adminId).stream()
        .map(AdminAgency::getAgencyId)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(HashSet::new));
  }

  /** A counsellor marked for deletion keeps the agencies it had, so its deletion stays pausable. */
  private Set<Long> agencyIdsOf(Consultant consultant) {
    var relations =
        consultant.getDeleteDate() == null
            ? consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(consultant.getId())
            : consultantAgencyRepository.findByConsultantId(consultant.getId());
    return relations.stream()
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
