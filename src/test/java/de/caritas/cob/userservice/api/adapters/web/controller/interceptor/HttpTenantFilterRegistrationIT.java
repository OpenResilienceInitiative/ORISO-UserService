package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.UserServiceApplication;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = UserServiceApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
class HttpTenantFilterRegistrationIT {

  @Autowired private ServletContext servletContext;

  @Autowired private FilterChainProxy securityFilterChain;

  @Test
  void httpTenantFilter_ShouldOnlyRunInsideSpringSecurityFilterChain() {
    assertThat(servletContext.getFilterRegistrations().values())
        .extracting(FilterRegistration::getClassName)
        .doesNotContain(HttpTenantFilter.class.getName());
  }

  @Test
  void httpTenantFilter_ShouldRunAfterBearerAuthentication() {
    List<Class<?>> securityFilterTypes =
        securityFilterChain.getFilterChains().stream()
            .flatMap(filterChain -> filterChain.getFilters().stream())
            .map(Object::getClass)
            .toList();

    assertThat(securityFilterTypes).containsOnlyOnce(HttpTenantFilter.class);
    assertThat(securityFilterTypes.indexOf(HttpTenantFilter.class))
        .isGreaterThan(securityFilterTypes.indexOf(BearerTokenAuthenticationFilter.class));
  }
}
