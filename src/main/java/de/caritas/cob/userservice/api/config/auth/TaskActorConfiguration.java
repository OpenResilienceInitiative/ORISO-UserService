package de.caritas.cob.userservice.api.config.auth;

import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakCustomConfig;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class TaskActorConfiguration {
  private final Environment environment;
  private final KeycloakCustomConfig legacyAdmin;
  private final IdentityClientConfig legacyIdentity;

  public TaskActorConfiguration(
      Environment environment,
      KeycloakCustomConfig legacyAdmin,
      IdentityClientConfig legacyIdentity) {
    this.environment = environment;
    this.legacyAdmin = legacyAdmin;
    this.legacyIdentity = legacyIdentity;
  }

  public TaskActorCredentials require(TaskPurpose purpose) {
    if (purpose == null || !isEnabled()) {
      throw unavailable();
    }
    var credentials = new EnumMap<TaskActor, TaskActorCredentials>(TaskActor.class);
    var clients = new HashSet<String>();
    var secrets = new HashSet<String>();
    var subjects = new HashSet<String>();
    var technical = legacyIdentity.getTechnicalUser();
    for (var actor : TaskActor.values()) {
      String prefix = "IDENTITY_" + actor.name();
      String client = environment.getProperty(prefix + "_CLIENT_ID");
      String secret = environment.getProperty(prefix + "_CLIENT_SECRET");
      String subject = environment.getProperty(prefix + "_SERVICE_SUBJECT");
      if (blank(client)
          || blank(secret)
          || blank(subject)
          || !clients.add(client)
          || !secrets.add(secret)
          || !subjects.add(subject)
          || matches(client, legacyAdmin.getAdminClientId(), legacyAdmin.getAppClientId())
          || matches(secret, legacyAdmin.getAdminClientSecret())
          || matches(subject, legacyAdmin.getAdminServiceSubject())
          || matches(subject, environment.getProperty("IDENTITY_TECHNICAL_USER_SERVICE_SUBJECT"))
          || (technical != null
              && (matches(client, technical.getClientId())
                  || matches(secret, technical.getClientSecret())))) {
        throw unavailable();
      }
      credentials.put(actor, new TaskActorCredentials(actor, client, secret, subject));
    }
    return credentials.get(purpose.actor());
  }

  public boolean isEnabled() {
    String model = environment.getProperty("TASK_IDENTITY_MODEL", "LEGACY");
    if (!Set.of("LEGACY", "FIVE_ACTORS").contains(model)) {
      throw unavailable();
    }
    return "FIVE_ACTORS".equals(model);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static boolean matches(String value, String... forbidden) {
    for (String candidate : forbidden) {
      if (value.equals(candidate)) return true;
    }
    return false;
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Configure five distinct native task actor bindings");
  }
}
