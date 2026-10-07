package de.caritas.cob.userservice.api.tenant;

import static de.caritas.cob.userservice.api.config.auth.UserRole.TECHNICAL;

import de.caritas.cob.userservice.api.config.auth.KeycloakRoles;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TechnicalOrSuperAdminUserTenantResolver implements TenantResolver {

  @org.springframework.beans.factory.annotation.Value(
      "${identity.tasks.notification-dispatch.client-id:}")
  private String notificationTaskClient;

  @org.springframework.beans.factory.annotation.Value(
      "${identity.tasks.notification-dispatch.service-subject:}")
  private String notificationTaskSubject;

  @org.springframework.beans.factory.annotation.Value("${identity.consultant-import.client-id:}")
  private String importTaskClient;

  @org.springframework.beans.factory.annotation.Value(
      "${identity.consultant-import.service-subject:}")
  private String importTaskSubject;

  @org.springframework.beans.factory.annotation.Value("${task.identity.audience:userservice}")
  private String taskIdentityAudience;

  @Override
  public Optional<Long> resolve(HttpServletRequest request) {
    var principal = request.getUserPrincipal();
    if (principal instanceof JwtAuthenticationToken verified
        && (new de.caritas.cob.userservice.api.config.auth.NotificationPreferencesTaskAuthorization(
                    notificationTaskClient, notificationTaskSubject, taskIdentityAudience)
                .permits(verified)
            || new de.caritas.cob.userservice.api.config.auth.ConsultantImportTaskAuthorization(
                    importTaskClient, importTaskSubject, taskIdentityAudience)
                .permits(verified))) return Optional.of(0L);
    return isTechnicalOrGlobalTenantAdmin(request) ? Optional.of(0L) : Optional.empty();
  }

  private boolean isTechnicalOrGlobalTenantAdmin(HttpServletRequest request) {
    Jwt token = getAccessToken(request);
    if (token == null) {
      return false;
    }
    // Only technical role should force global tenant context.
    // Super-admin is still resolved as tenant 0 through AccessTokenTenantResolver claim parsing.
    return containsRole(token, TECHNICAL.getValue());
  }

  private Jwt getAccessToken(HttpServletRequest request) {
    var principal = request.getUserPrincipal();
    if (!(principal instanceof JwtAuthenticationToken jwtToken)) {
      log.debug(
          "UserPrincipal is not a JwtAuthenticationToken (was: {}), treating as non-technical user",
          principal == null ? "null" : principal.getClass().getName());
      return null;
    }
    return jwtToken.getToken();
  }

  private boolean containsRole(Jwt token, String expectedRole) {
    return KeycloakRoles.of(token.getClaims()).contains(expectedRole);
  }

  @Override
  public boolean canResolve(HttpServletRequest request) {
    return resolve(request).isPresent();
  }
}
