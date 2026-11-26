package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A direct-link guest account is deleted after the conversation, but a signed token stays valid
 * until it expires. Every request of such a token is refused with 401 and the reason, so the app
 * can sign the guest out and say why (ORISO-Frontend#2550).
 */
@Slf4j
public class TemporaryAccountDeletedAccessFilter extends OncePerRequestFilter {

  static final String REASON_HEADER = "X-Reason";

  private final Predicate<String> deletionStarted;

  /**
   * @param deletionStarted tells by Keycloak user id whether the account's deletion started
   */
  public TemporaryAccountDeletedAccessFilter(Predicate<String> deletionStarted) {
    this.deletionStarted = deletionStarted;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getUserPrincipal() instanceof JwtAuthenticationToken authentication
        && isDeleted(authentication.getToken().getSubject())) {
      response.setHeader(REASON_HEADER, HttpStatusExceptionReason.TEMPORARY_ACCOUNT_DELETED.name());
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    chain.doFilter(request, response);
  }

  private boolean isDeleted(String userId) {
    if (userId == null) {
      return false;
    }
    try {
      return deletionStarted.test(userId);
    } catch (RuntimeException unavailable) {
      // Fail open: the later checks still apply, and a lookup error must not lock out everyone.
      log.warn(
          "Temporary account deletion check unavailable: {}",
          unavailable.getClass().getSimpleName());
      return false;
    }
  }
}
