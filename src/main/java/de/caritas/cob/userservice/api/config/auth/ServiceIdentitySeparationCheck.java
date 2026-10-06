package de.caritas.cob.userservice.api.config.auth;

import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakCustomConfig;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import jakarta.annotation.PostConstruct;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Backend technical, admin and human clients must remain separate identities. */
@Component
@RequiredArgsConstructor
public class ServiceIdentitySeparationCheck {
  private final @NonNull KeycloakCustomConfig keycloakCustomConfig;
  private final @NonNull IdentityClientConfig identityClientConfig;

  @PostConstruct
  public void verifyBackendClients() {
    var technical = identityClientConfig.getTechnicalUser();
    if (technical == null
        || blank(technical.getClientId())
        || blank(technical.getClientSecret())
        || blank(keycloakCustomConfig.getAdminClientId())
        || blank(keycloakCustomConfig.getAdminClientSecret())
        || blank(keycloakCustomConfig.getAppClientId())
        || technical.getClientId().equals(keycloakCustomConfig.getAdminClientId())
        || technical.getClientId().equals(keycloakCustomConfig.getAppClientId())
        || keycloakCustomConfig.getAdminClientId().equals(keycloakCustomConfig.getAppClientId())
        || technical.getClientSecret().equals(keycloakCustomConfig.getAdminClientSecret())) {
      throw new IllegalStateException(
          "Configure distinct confidential technical/admin clients and secrets, separate from the human app client");
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
