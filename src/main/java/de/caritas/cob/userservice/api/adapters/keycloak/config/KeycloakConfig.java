package de.caritas.cob.userservice.api.adapters.keycloak.config;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.exception.keycloak.KeycloakException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.URL;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.client.RestTemplate;

@Data
@Configuration
@Validated
@ConfigurationProperties(prefix = "keycloak")
public class KeycloakConfig {

  @Bean("keycloakRestTemplate")
  public RestTemplate keycloakRestTemplate(RestTemplateBuilder restTemplateBuilder) {
    return restTemplateBuilder.build();
  }

  @Bean
  public AuthenticatedUser authenticatedUser(HttpServletRequest request) {
    var authenticatedUser = new AuthenticatedUser();

    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken jwtAuth) {
      Jwt jwt = jwtAuth.getToken();
      Map<String, Object> claimMap = jwt.getClaims();

      try {
        if (claimMap.containsKey("username")) {
          authenticatedUser.setUsername(claimMap.get("username").toString());
        } else if (claimMap.containsKey("preferred_username")) {
          authenticatedUser.setUsername(claimMap.get("preferred_username").toString());
        }
        authenticatedUser.setUserId(jwt.getSubject());
        authenticatedUser.setAccessToken(jwt.getTokenValue());
        authenticatedUser.setRoles(extractRealmRoles(claimMap));
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

  private Set<String> extractRealmRoles(Map<String, Object> claims) {
    Object realmAccess = claims.get("realm_access");
    if (realmAccess instanceof Map<?, ?> realmAccessMap) {
      Object roles = realmAccessMap.get("roles");
      if (roles instanceof Collection<?> rolesCollection) {
        return rolesCollection.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .collect(Collectors.toSet());
      }
    }
    return Set.of();
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

  @URL private String authServerUrl;

  @NotBlank private String realm;

  @NotBlank private String resource;

  @NotBlank private String principalAttribute;

  @NotNull private Boolean cors;

  private KeycloakCustomConfig config;
}
