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

  @lombok.ToString.Exclude @NotBlank private String adminClientSecret;

  @NotBlank private String adminClientId;

  @NotBlank private String adminServiceSubject;

  @NotBlank private String appClientId;
}
