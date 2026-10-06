package de.caritas.cob.userservice.api.picture;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Prevent multipart resolution from spooling bytes before the controller can reject them. */
public class PictureRequestFilter extends OncePerRequestFilter {
  private final RequestMatcher pictureRoutes =
      new OrRequestMatcher(
          PathPatternRequestMatcher.withDefaults()
              .matcher("/useradmin/consultants/{consultantId}/picture"),
          PathPatternRequestMatcher.withDefaults()
              .matcher("/service/useradmin/consultants/{consultantId}/picture"),
          // Issue #1049: the onboarding wizard's raw-body upload needs the same guard.
          PathPatternRequestMatcher.withDefaults()
              .matcher("/users/account-invites/{token}/onboarding/picture"),
          PathPatternRequestMatcher.withDefaults()
              .matcher("/service/users/account-invites/{token}/onboarding/picture"));

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // Use security/MVC decoded path-segment semantics, including encoded literal aliases.
    return !pictureRoutes.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String type = request.getContentType();
    boolean multipart =
        type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
    boolean unsupportedPut =
        request.getMethod().equals("PUT")
            && !Set.of("image/png", "image/jpeg").contains(type == null ? "" : type);
    if (multipart || unsupportedPut) {
      response.setStatus(415);
      response.setContentType("application/json");
      response.setHeader("Cache-Control", "no-store");
      response.getWriter().write("{\"reason\":\"PICTURE_UNSUPPORTED_TYPE\"}");
      return;
    }
    chain.doFilter(request, response);
  }
}
