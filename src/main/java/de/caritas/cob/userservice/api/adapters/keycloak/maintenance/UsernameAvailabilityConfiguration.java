package de.caritas.cob.userservice.api.adapters.keycloak.maintenance;

import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;

/** Reuse the coordinated C startup fence and its sole broker, key and actual realm binding. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ORISO_ACCOUNT_LIFECYCLE_ENABLED", havingValue = "true")
public class UsernameAvailabilityConfiguration {
  @Bean
  ScopedUsernameAvailability scopedUsernameAvailability(
      Environment environment,
      @Qualifier("keycloakRestTemplate") RestTemplate http,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      KeycloakConfig keycloak) {
    if (!"FIVE_ACTORS".equals(environment.getProperty("TASK_IDENTITY_MODEL"))
        || !required(environment, "ORISO_ACCOUNT_LIFECYCLE_REALM").equals(keycloak.getRealm()))
      throw invalid();
    actors.require(TaskPurpose.ACCOUNT_PROVISIONING);
    String url =
        environment.getProperty(
            "ORISO_IDENTITY_INTERNAL_AUTH_SERVER_URL", keycloak.getAuthServerUrl());
    if (url == null || url.isBlank()) throw invalid();
    return new KeycloakUsernameAvailability(
        http,
        actors,
        broker,
        codec,
        url,
        keycloak.getRealm(),
        required(environment, "KEYCLOAK_REALM_INTERNAL_ID"));
  }

  private static String required(Environment environment, String name) {
    String value = environment.getProperty(name);
    if (value == null || value.isBlank()) throw invalid();
    return value;
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("Configure the bounded username availability realm binding");
  }
}
