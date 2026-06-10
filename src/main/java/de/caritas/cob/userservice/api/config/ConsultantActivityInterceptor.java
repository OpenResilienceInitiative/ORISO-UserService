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
 * Heartbeat keep-alive for consultant live-chat availability. On every authenticated consultant
 * request it refreshes the consultant's availability TTL — but only if they are already marked
 * available (via the Live Chat toggle). It never marks a consultant available, so a consultant who
 * disabled Live Chat is not kept counted just because their app keeps polling.
 *
 * <p>Dependencies are resolved via {@link ObjectProvider} so the bean constructs in web-layer test
 * slices ({@code @WebMvcTest}) that do not load the service beans; there it simply no-ops.
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
        registry.refreshIfAvailable(user.getUserId());
      }
    } catch (Exception ex) {
      // Never block a request because of best-effort activity tracking.
    }
    return true;
  }
}
