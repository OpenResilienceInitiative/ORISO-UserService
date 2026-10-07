package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Rechecks verified human permission and persisted target; no missing-request privilege fallback.
 */
@Component
@RequiredArgsConstructor
public class IdentityMaintenanceOrigins {
  private final AdminScope scope;
  private final AdminRepository admins;
  private final ConsultantRepository consultants;
  private final UserRepository users;

  /**
   * Preserve authorized local platform metadata without granting native protected-account reads.
   */
  public boolean protectedPlatformStatusUnavailable(String id) {
    var admin = TenantContext.supplyAcrossTenants(() -> admins.findById(id));
    if (admin.isEmpty()
        || admin.get().getType() != de.caritas.cob.userservice.api.model.Admin.AdminType.TENANT
        || !java.util.Objects.equals(admin.get().getTenantId(), 0L)) return false;
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken verified) || !verified.isAuthenticated())
      throw new AccessDeniedException("Platform metadata needs verified administrator authority");
    if (id.equals(verified.getToken().getSubject())) return false;
    IdentityCommandAuthorization.verifiedAdministratorMetadata(verified);
    scope.assertMay(AdminScope.Target.admin(id));
    return true;
  }

  public IdentityCommandAuthorization current(
      String id, String operation, Collection<String> roles) {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken verified) || !verified.isAuthenticated())
      throw new AccessDeniedException("Account maintenance needs an explicit verified origin");
    boolean self =
        id.equals(verified.getToken().getSubject()) && !operation.equals("account.roles");
    var admin = TenantContext.supplyAcrossTenants(() -> admins.findById(id));
    if (admin.isPresent())
      return self
          ? IdentityCommandAuthorization.selfService(verified.getToken(), admin.get(), operation)
          : IdentityCommandAuthorization.checkedHuman(
              verified, scope, admin.get(), operation, roles);
    var consultant = TenantContext.supplyAcrossTenants(() -> consultants.findById(id));
    if (consultant.isPresent())
      return self
          ? IdentityCommandAuthorization.selfService(
              verified.getToken(), consultant.get(), operation)
          : IdentityCommandAuthorization.checkedHuman(
              verified, scope, consultant.get(), operation, roles);
    var user = TenantContext.supplyAcrossTenants(() -> users.findById(id));
    if (user.isPresent())
      return self
          ? IdentityCommandAuthorization.selfService(verified.getToken(), user.get(), operation)
          : IdentityCommandAuthorization.checkedHuman(
              verified, scope, user.get(), operation, roles);
    throw new AccessDeniedException("Account maintenance target has no verified local ownership");
  }
}
