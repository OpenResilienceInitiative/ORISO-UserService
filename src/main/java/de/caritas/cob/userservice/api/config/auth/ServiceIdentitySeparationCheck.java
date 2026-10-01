package de.caritas.cob.userservice.api.config.auth;

import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakCustomConfig;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import java.util.Locale;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup guard for ORISO-Helm#367: the Keycloak Admin REST identity and the service-to-service
 * technical user are meant to be two separate accounts, so the service login does not inherit
 * realm-management rights. Only a warning for now, because the live configuration is unverified.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ServiceIdentitySeparationCheck {

  private final @NonNull KeycloakCustomConfig keycloakCustomConfig;
  private final @NonNull IdentityClientConfig identityClientConfig;

  @EventListener(ApplicationReadyEvent.class)
  public void warnIfKeycloakAdminIsTheTechnicalUser() {
    var technicalUser = identityClientConfig.getTechnicalUser();
    String adminUsername = normalize(keycloakCustomConfig.getAdminUsername());
    String technicalUsername =
        technicalUser == null ? null : normalize(technicalUser.getUsername());
    if (adminUsername == null || !adminUsername.equals(technicalUsername)) {
      return;
    }
    log.warn(
        "The Keycloak admin identity (keycloak.config.admin-username / "
            + "KEYCLOAK_CONFIG_ADMIN_USERNAME) is the same account as the service-to-service "
            + "technical user (identity.technical-user.username / "
            + "IDENTITY_TECHNICAL_USER_USERNAME). Configure two separate identities: the "
            + "technical user must not hold realm-management roles (ORISO-Helm#367).");
  }

  private static String normalize(String username) {
    if (username == null || username.isBlank()) {
      return null;
    }
    return username.trim().toLowerCase(Locale.ROOT);
  }
}
