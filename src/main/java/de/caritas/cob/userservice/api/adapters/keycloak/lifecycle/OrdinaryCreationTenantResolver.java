package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

/** Resolves the concrete owner on single-tenant installations from current server facts. */
public final class OrdinaryCreationTenantResolver {
  private final TenantService tenants;
  private final AgencyService agencies;
  private final IdentityClientConfig identities;

  public OrdinaryCreationTenantResolver(
      TenantService tenants, AgencyService agencies, IdentityClientConfig identities) {
    this.tenants = Objects.requireNonNull(tenants);
    this.agencies = Objects.requireNonNull(agencies);
    this.identities = Objects.requireNonNull(identities);
  }

  public Long resolve(Long requestedTenant, Collection<Long> agencyIds) {
    if (!identities.isFiveActorMode()) throw unavailable();
    String token = identities.taskAccessToken(TaskPurpose.RUNTIME_POLICY);
    if (token == null || token.isBlank()) throw unavailable();
    Long concrete;
    var ids = agencyIds == null ? new HashSet<Long>() : new HashSet<>(agencyIds);
    if (ids.contains(null) || ids.stream().anyMatch(id -> id <= 0)) throw denied();
    if (ids.isEmpty()) {
      var configured = tenants.getSingleTenancyTenantDataFresh(token);
      concrete = configured == null ? null : configured.getId();
    } else {
      var current = agencies.getAgenciesForOrdinaryCreation(List.copyOf(ids), token);
      if (current == null
          || current.size() != ids.size()
          || current.contains(null)
          || !current.stream()
              .map(a -> a.getId())
              .collect(java.util.stream.Collectors.toSet())
              .equals(ids)) throw unavailable();
      var owners =
          current.stream().map(a -> a.getTenantId()).collect(java.util.stream.Collectors.toSet());
      if (owners.size() != 1) throw denied();
      concrete = owners.iterator().next();
    }
    if (concrete == null || concrete <= 0) throw unavailable();
    if (requestedTenant != null && !requestedTenant.equals(concrete)) throw denied();
    return concrete;
  }

  private static AccessDeniedException denied() {
    return new AccessDeniedException("Ordinary creation differs from its current tenant owner");
  }

  private static ResponseStatusException unavailable() {
    return new ResponseStatusException(
        HttpStatus.BAD_GATEWAY, "Concrete single-tenant identity unavailable");
  }
}
