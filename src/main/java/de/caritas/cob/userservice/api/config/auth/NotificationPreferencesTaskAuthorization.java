package de.caritas.cob.userservice.api.config.auth;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.security.authorization.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** A role alone never grants the machine endpoint to a human or another service account. */
public final class NotificationPreferencesTaskAuthorization
    implements AuthorizationManager<RequestAuthorizationContext> {
  private final String client;
  private final String subject;
  private final String audience;

  public NotificationPreferencesTaskAuthorization(String client, String subject, String audience) {
    this.client = client;
    this.subject = subject;
    this.audience = audience;
  }

  @Override
  public AuthorizationDecision authorize(
      Supplier<? extends Authentication> authentication, RequestAuthorizationContext request) {
    return new AuthorizationDecision(permits(authentication.get()));
  }

  public boolean permits(Authentication auth) {
    if (client == null
        || client.isBlank()
        || subject == null
        || subject.isBlank()
        || audience == null
        || audience.isBlank()) return false;
    if (!(auth instanceof JwtAuthenticationToken jwtAuth) || !auth.isAuthenticated()) return false;
    var jwt = jwtAuth.getToken();
    var realm = jwt.getClaimAsMap("realm_access");
    Object value = realm == null ? null : realm.get("roles");
    var resources = jwt.getClaimAsMap("resource_access");
    boolean allowed =
        subject.equals(jwt.getSubject())
            && client.equals(jwt.getClaimAsString("azp"))
            && jwt.getAudience().contains(audience)
            && value instanceof Collection<?> roles
            && new HashSet<>(roles).equals(TaskIdentity.NOTIFICATION_DISPATCH.roles())
            && (resources == null || resources.isEmpty());
    return allowed;
  }
}
