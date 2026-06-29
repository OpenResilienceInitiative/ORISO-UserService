package de.caritas.cob.userservice.api.adapters.keycloak.config;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.config.RestTemplateTimeouts;
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
import org.keycloak.adapters.KeycloakConfigResolver;
import org.keycloak.adapters.springboot.KeycloakSpringBootConfigResolver;
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

  @Bean("keycloakRestTemplate")
  public RestTemplate keycloakRestTemplate(RestTemplateBuilder restTemplateBuilder) {
    return restTemplateBuilder
        .connectTimeout(RestTemplateTimeouts.CONNECT_TIMEOUT)
        .readTimeout(RestTemplateTimeouts.READ_TIMEOUT)
        .build();
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
          Object usernameClaim =
              claimMap.getOrDefault(
                  "username", claimMap.getOrDefault(principalAttribute, authToken.getName()));
          authenticatedUser.setUsername(
              usernameTranscoder.decodeUsername(usernameClaim.toString()));
          authenticatedUser.setUserId(jwt.getSubject());
          authenticatedUser.setAccessToken(jwt.getTokenValue());
          authenticatedUser.setRoles(extractRealmRoles(jwt));
          if (claimMap.containsKey("tenantId")) {
            authenticatedUser.setTenantId(Long.valueOf(claimMap.get("tenantId").toString()));
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

  @Bean
  public Keycloak keycloak() {
    return KeycloakBuilder.builder()
        .serverUrl(authServerUrl)
        .realm(realm)
        .username(config.getAdminUsername())
        .password(config.getAdminPassword())
        .clientId(config.getAdminClientId())
        .build();
  }

  /**
   * Use the KeycloakSpringBootConfigResolver to be able to save the Keycloak settings in the spring
   * application properties.
   */
  @Bean
  public KeycloakConfigResolver keyCloakConfigResolver() {
    return new KeycloakSpringBootConfigResolver();
  }

  @URL private String authServerUrl;

  @NotBlank private String realm;

  @NotBlank private String resource;

  @NotBlank private String principalAttribute;

  @NotNull private Boolean cors;

  private KeycloakCustomConfig config;
}
