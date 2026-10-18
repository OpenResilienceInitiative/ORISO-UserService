package de.caritas.cob.userservice.api.config.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "identity.technical-user")
public class TechnicalUserConfig {

  @NotBlank private String clientId;

  @lombok.ToString.Exclude @NotBlank private String clientSecret;
}
