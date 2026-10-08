package de.caritas.cob.userservice.api.config.auth;

import java.util.function.Supplier;
import org.springframework.security.authorization.*;
import org.springframework.security.core.Authentication;
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
    return ExactTaskBinding.permits(
        auth, client, subject, audience, TaskIdentity.NOTIFICATION_DISPATCH.roles());
  }
}
