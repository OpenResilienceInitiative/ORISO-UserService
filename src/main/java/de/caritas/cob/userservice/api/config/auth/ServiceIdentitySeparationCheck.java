package de.caritas.cob.userservice.api.config.auth;

import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakCustomConfig;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import jakarta.annotation.PostConstruct;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Backend technical, admin and human clients must remain separate identities. */
@Component
@RequiredArgsConstructor
public class ServiceIdentitySeparationCheck
    implements org.springframework.beans.factory.SmartInitializingSingleton {
  private final @NonNull KeycloakCustomConfig keycloakCustomConfig;
  private final @NonNull IdentityClientConfig identityClientConfig;
  private final @NonNull Environment environment;

  @Value("${TASK_IDENTITY_MODEL:LEGACY}")
  private String taskIdentityModel = "LEGACY";

  private volatile boolean verifiedLegacy;
  private volatile boolean verifiedActors;
  private volatile boolean verifiedInstallation;

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.context.ApplicationContext applicationContext;

  /** Read-only public capability; it grants no account authority. */
  public Boolean scopedAccountCommands() {
    if (verifiedActors
        && verifiedInstallation
        && "FIVE_ACTORS".equals(taskIdentityModel)
        && identityClientConfig.isFiveActorMode()) return Boolean.TRUE;
    return verifiedLegacy
            && "LEGACY".equals(taskIdentityModel)
            && !identityClientConfig.isFiveActorMode()
        ? Boolean.FALSE
        : null;
  }

  @PostConstruct
  public void verifyBackendClients() {
    verifiedLegacy = false;
    verifiedActors = false;
    verifiedInstallation = false;
    if ("FIVE_ACTORS".equals(taskIdentityModel)) {
      if (!identityClientConfig.isFiveActorMode())
        throw new IllegalStateException("Inconsistent task identity model");
      // Routine callers may use the staged actors only after every actor binding is present.
      // Account lifecycle commands retain their own receiver-level gate in TaskActorTokenBroker.
      var taskActors =
          new TaskActorConfiguration(environment, keycloakCustomConfig, identityClientConfig);
      for (TaskPurpose purpose : TaskPurpose.values()) {
        taskActors.require(purpose);
      }
      verifiedActors = true;
      return;
    }
    if (!"LEGACY".equals(taskIdentityModel)) {
      throw new IllegalStateException("Unsupported task identity model");
    }
    var technical = identityClientConfig.getTechnicalUser();
    if (technical == null
        || blank(technical.getClientId())
        || blank(technical.getClientSecret())
        || blank(keycloakCustomConfig.getAdminClientId())
        || blank(keycloakCustomConfig.getAdminClientSecret())
        || blank(keycloakCustomConfig.getAdminServiceSubject())
        || blank(keycloakCustomConfig.getAppClientId())
        || technical.getClientId().equals(keycloakCustomConfig.getAdminClientId())
        || technical.getClientId().equals(keycloakCustomConfig.getAppClientId())
        || keycloakCustomConfig.getAdminClientId().equals(keycloakCustomConfig.getAppClientId())
        || technical.getClientSecret().equals(keycloakCustomConfig.getAdminClientSecret())) {
      throw new IllegalStateException(
          "Configure distinct confidential technical/admin clients and secrets, separate from the human app client");
    }
    verifiedLegacy = true;
  }

  @Override
  public void afterSingletonsInstantiated() {
    verifiedInstallation = false;
    if (!verifiedActors
        || !"true".equals(environment.getProperty("ORISO_ACCOUNT_LIFECYCLE_ENABLED"))) return;
    if (applicationContext == null)
      throw new IllegalStateException("Missing scoped account installation");
    new ScopedAccountCommandInstallation(applicationContext).verify();
    verifiedInstallation = true;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
