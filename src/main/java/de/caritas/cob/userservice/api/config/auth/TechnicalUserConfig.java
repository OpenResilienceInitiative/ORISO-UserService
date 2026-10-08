package de.caritas.cob.userservice.api.config.auth;

import lombok.Data;

/** Compatibility value for older consumers; runtime credentials belong to task identities. */
@Data
public class TechnicalUserConfig {

  private String clientId;

  @lombok.ToString.Exclude private String clientSecret;
}
