package de.caritas.cob.userservice.api.adapters.matrix.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration for Matrix Synapse integration. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "matrix")
public class MatrixConfig {

  private String apiUrl = "http://matrix-synapse:8008";
  private String registrationSharedSecret = "caritas-registration-secret-2025";
  private String serverName = "caritas.local";

  /**
   * Gets the full API URL for a given endpoint.
   *
   * @param endpoint the endpoint path
   * @return the full URL
   */
  public String getApiUrl(String endpoint) {
    return apiUrl + endpoint;
  }
}
