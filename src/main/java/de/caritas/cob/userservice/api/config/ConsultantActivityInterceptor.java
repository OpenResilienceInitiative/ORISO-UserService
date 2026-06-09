package de.caritas.cob.userservice.api.config;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.availability.ConsultantActivityRegistry;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Stamps consultant liveness on every authenticated consultant request, feeding {@link
 * ConsultantActivityRegistry}. This is the real-time signal behind live-chat availability.
 *
 * <p>Dependencies are resolved via {@link ObjectProvider} so the bean still constructs in web-layer
 * test slices ({@code @WebMvcTest}) that do not load the service beans; there it simply no-ops.
 */
@Component
@RequiredArgsConstructor
public class ConsultantActivityInterceptor implements HandlerInterceptor {

  private final ObjectProvider<AuthenticatedUser> authenticatedUser;
  private final ObjectProvider<ConsultantActivityRegistry> consultantActivityRegistry;

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    try {
      AuthenticatedUser user = authenticatedUser.getIfAvailable();
      ConsultantActivityRegistry registry = consultantActivityRegistry.getIfAvailable();
      if (user != null && registry != null && user.isConsultant() && user.getUserId() != null) {
        registry.recordActivity(user.getUserId());
      }
    } catch (Exception ex) {
      // Never block a request because of best-effort activity tracking.
    }
    return true;
  }
}
