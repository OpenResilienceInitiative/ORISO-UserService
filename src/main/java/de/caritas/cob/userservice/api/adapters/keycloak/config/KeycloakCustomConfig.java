package de.caritas.cob.userservice.api.adapters.keycloak.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Data
@Configuration
@Validated
@ConfigurationProperties(prefix = "keycloak.config")
public class KeycloakCustomConfig {

  // Compatibility metadata only; no native runtime Admin client is constructed.
  @lombok.ToString.Exclude private String adminClientSecret;

  private String adminClientId;

  private String adminServiceSubject;

  @NotBlank private String appClientId;
}
