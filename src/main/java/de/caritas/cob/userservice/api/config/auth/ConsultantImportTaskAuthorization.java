package de.caritas.cob.userservice.api.config.auth;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.security.authorization.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** Receiving-only importer; a human role or an unrelated service token cannot import accounts. */
public final class ConsultantImportTaskAuthorization
    implements AuthorizationManager<RequestAuthorizationContext> {
  private final String client;
  private final String subject;
  private final String audience;

  public ConsultantImportTaskAuthorization(String client, String subject, String audience) {
    this.client = client;
    this.subject = subject;
    this.audience = audience;
  }

  public boolean permits(Authentication auth) {
    if (client == null
        || client.isBlank()
        || subject == null
        || subject.isBlank()
        || audience == null
        || audience.isBlank()
        || !(auth instanceof JwtAuthenticationToken verified)
        || !verified.isAuthenticated()) return false;
    var jwt = verified.getToken();
    var realm = jwt.getClaimAsMap("realm_access");
    var resources = jwt.getClaimAsMap("resource_access");
    Object value = realm == null ? null : realm.get("roles");
    return subject.equals(jwt.getSubject())
        && client.equals(jwt.getClaimAsString("azp"))
        && jwt.getAudience().contains(audience)
        && value instanceof Collection<?> roles
        && new HashSet<>(roles).equals(Set.of("consultant-import"))
        && (resources == null || resources.isEmpty());
  }

  @Override
  public AuthorizationDecision authorize(
      Supplier<? extends Authentication> auth, RequestAuthorizationContext request) {
    return new AuthorizationDecision(permits(auth.get()));
  }
}
