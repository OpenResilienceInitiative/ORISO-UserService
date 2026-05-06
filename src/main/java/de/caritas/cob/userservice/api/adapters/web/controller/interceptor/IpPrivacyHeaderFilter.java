package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security-03 foundation: in STRICT mode, strip raw client IP forwarding headers from application
 * request handling.
 */
@Component
public class IpPrivacyHeaderFilter extends OncePerRequestFilter {

  private static final Set<String> FORBIDDEN_HEADERS =
      Set.of("x-real-ip", "x-forwarded-for", "forwarded");

  @Value("${privacy.ipMode:STANDARD}")
  private String ipMode;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!"STRICT".equalsIgnoreCase(ipMode)) {
      filterChain.doFilter(request, response);
      return;
    }
    filterChain.doFilter(new StrictIpHeaderRequestWrapper(request), response);
  }

  private static class StrictIpHeaderRequestWrapper extends HttpServletRequestWrapper {

    StrictIpHeaderRequestWrapper(HttpServletRequest request) {
      super(request);
    }

    @Override
    public String getHeader(String name) {
      if (isForbiddenHeader(name)) {
        return null;
      }
      return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      if (isForbiddenHeader(name)) {
        return Collections.emptyEnumeration();
      }
      return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
      Enumeration<String> names = super.getHeaderNames();
      if (names == null) {
        return Collections.emptyEnumeration();
      }
      List<String> filtered = new ArrayList<>();
      while (names.hasMoreElements()) {
        String name = names.nextElement();
        if (!isForbiddenHeader(name)) {
          filtered.add(name);
        }
      }
      return Collections.enumeration(filtered);
    }

    private static boolean isForbiddenHeader(String name) {
      if (name == null) {
        return false;
      }
      return FORBIDDEN_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }
  }
}
