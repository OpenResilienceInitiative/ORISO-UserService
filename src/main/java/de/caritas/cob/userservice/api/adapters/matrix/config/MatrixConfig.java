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
  private String adminUsername;
  private String adminPassword;

  /**
   * When {@code true}, consultant live-chat availability is derived from real-time Matrix presence.
   * Set to {@code false} to fall back to the legacy RocketChat presence / best-effort signal (e.g.
   * if consultant clients do not keep a Matrix sync open and presence is therefore unreliable).
   */
  private boolean presenceEnabled = true;

  /**
   * Maximum time since a consultant's last Matrix activity for them to still count as "available"
   * for live chat. Matrix presence is sticky (a closed client lingers as {@code online}/{@code
   * unavailable} for a long time), so availability is based on {@code last_active_ago} rather than
   * the presence string alone. Defaults to 5 minutes.
   */
  private long presenceActiveThresholdMs = 300_000L;

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
