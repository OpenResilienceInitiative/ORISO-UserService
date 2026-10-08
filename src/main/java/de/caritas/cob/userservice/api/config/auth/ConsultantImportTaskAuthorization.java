package de.caritas.cob.userservice.api.config.auth;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.security.authorization.*;
import org.springframework.security.core.Authentication;
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
    return ExactTaskBinding.permits(auth, client, subject, audience, Set.of("consultant-import"));
  }

  @Override
  public AuthorizationDecision authorize(
      Supplier<? extends Authentication> auth, RequestAuthorizationContext request) {
    return new AuthorizationDecision(permits(auth.get()));
  }
}
