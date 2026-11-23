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

  // No defaults: MATRIX_API_URL, MATRIX_REGISTRATION_SHARED_SECRET and MATRIX_SERVER_NAME come from
  // the environment; ConfigurationValidator refuses to start without the URL and the secret.
  private String apiUrl;
  private String registrationSharedSecret;
  private String serverName;
  private String adminUsername;
  private String adminPassword;

  /**
   * Externally provisioned Synapse admin token used only by availability GET requests. The token
   * itself has admin privileges; this path never logs in, registers accounts or renews it.
   */
  private String availabilityAdminAccessToken;

  private boolean encryptionEnabled = false;

  /**
   * When {@code true}, consultant live-chat availability is derived from real-time Matrix presence.
   * Set to {@code false} when presence must be treated as unavailable, for example when consultant
   * clients do not keep a Matrix sync open.
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
