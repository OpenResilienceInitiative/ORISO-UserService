package de.caritas.cob.userservice.api.adapters.keycloak.config;

import static de.caritas.cob.userservice.api.config.RestTemplateTimeouts.CONNECT_TIMEOUT;
import static de.caritas.cob.userservice.api.config.RestTemplateTimeouts.READ_TIMEOUT;
import static java.util.Objects.nonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import de.caritas.cob.userservice.api.exception.keycloak.KeycloakException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Data;
import org.hibernate.validator.constraints.URL;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.engines.ClientHttpEngineBuilder43;
import org.jboss.resteasy.client.jaxrs.engines.ManualClosingApacheHttpClient43Engine;
import org.keycloak.admin.client.ClientBuilderWrapper;
import org.keycloak.admin.client.JacksonProvider;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.WebApplicationContext;

@Data
@Configuration
@Validated
@ConfigurationProperties(prefix = "keycloak")
public class KeycloakConfig {

  private static final String USERNAME_CLAIM = "username";
  private static final String TENANT_ID_CLAIM = "tenantId";

  @Bean("keycloakRestTemplate")
  public RestTemplate keycloakRestTemplate(RestTemplateBuilder restTemplateBuilder) {
    return restTemplateBuilder.connectTimeout(CONNECT_TIMEOUT).readTimeout(READ_TIMEOUT).build();
  }

  @Bean
  @Scope(scopeName = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
  public AuthenticatedUser authenticatedUser(
      HttpServletRequest request, UsernameTranscoder usernameTranscoder) {
    var userPrincipal = request.getUserPrincipal();
    var authenticatedUser = new AuthenticatedUser();

    if (nonNull(userPrincipal)) {
      try {
        if (userPrincipal instanceof JwtAuthenticationToken authToken) {
          Jwt jwt = authToken.getToken();
          Map<String, Object> claimMap = jwt.getClaims();
          String usernameClaim =
              resolveUsernameClaim(
                  claimMap,
                  resolveClaimValue(
                      claimMap.getOrDefault(principalAttribute, authToken.getName())));
          authenticatedUser.setUsername(usernameTranscoder.decodeUsername(usernameClaim));
          authenticatedUser.setUserId(jwt.getSubject());
          authenticatedUser.setAccessToken(jwt.getTokenValue());
          authenticatedUser.setRoles(extractRealmRoles(jwt));
          var tenantIdClaim = resolveTenantIdClaim(claimMap);
          if (nonNull(tenantIdClaim)) {
            authenticatedUser.setTenantId(Long.valueOf(tenantIdClaim));
          }
        }
      } catch (Exception exception) {
        throw new KeycloakException("Keycloak data missing.", exception);
      }

      var authorities =
          SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
              .map(Object::toString)
              .collect(Collectors.toSet());
      authenticatedUser.setGrantedAuthorities(authorities);
    }

    return authenticatedUser;
  }

  @SuppressWarnings("unchecked")
  private Set<String> extractRealmRoles(Jwt jwt) {
    Object realmAccess = jwt.getClaims().get("realm_access");
    if (realmAccess instanceof Map<?, ?> realmAccessMap) {
      Object rolesClaim = realmAccessMap.get("roles");
      if (rolesClaim instanceof Collection<?> roles) {
        return roles.stream().map(Object::toString).collect(Collectors.toSet());
      }
    }
    return new HashSet<>();
  }

  String resolveUsernameClaim(Map<String, Object> claimMap, String preferredUsername) {
    var username = resolveClaimValue(claimMap.get(USERNAME_CLAIM));
    return hasText(username) ? username : resolveClaimValue(preferredUsername);
  }

  String resolveTenantIdClaim(Map<String, Object> claimMap) {
    return resolveClaimValue(claimMap.get(TENANT_ID_CLAIM));
  }

  private String resolveClaimValue(Object claimValue) {
    if (claimValue instanceof Collection<?>) {
      return ((Collection<?>) claimValue)
          .stream().map(this::resolveClaimValue).filter(this::hasText).findFirst().orElse(null);
    }
    return claimValue == null ? null : claimValue.toString();
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  @Bean
  public Keycloak keycloak(OutboundHttpMetrics outboundHttpMetrics) {
    return KeycloakBuilder.builder()
        .serverUrl(authServerUrl)
        .realm(realm)
        .username(config.getAdminUsername())
        .password(config.getAdminPassword())
        .clientId(config.getAdminClientId())
        .resteasyClient(keycloakAdminHttpClientBuilder(outboundHttpMetrics).build())
        .build();
  }

  @SuppressWarnings("removal")
  ResteasyClientBuilder keycloakAdminHttpClientBuilder(OutboundHttpMetrics outboundHttpMetrics) {
    var builder = (ResteasyClientBuilder) ClientBuilderWrapper.create(null, false);
    builder
        .connectTimeout(CONNECT_TIMEOUT.toMillis(), MILLISECONDS)
        .readTimeout(READ_TIMEOUT.toMillis(), MILLISECONDS)
        .register(JacksonProvider.class, 100);
    var delegateEngine =
        (ManualClosingApacheHttpClient43Engine)
            new ClientHttpEngineBuilder43().resteasyClientBuilder(builder).build();
    var measuredEngine = new KeycloakAdminHttpMetricsEngine(delegateEngine, outboundHttpMetrics);
    builder.register(measuredEngine);
    builder.httpEngine(measuredEngine);
    return builder;
  }

  @URL private String authServerUrl;

  @NotBlank private String realm;

  @NotBlank private String resource;

  @NotBlank private String principalAttribute;

  @NotNull private Boolean cors;

  private KeycloakCustomConfig config;
}
