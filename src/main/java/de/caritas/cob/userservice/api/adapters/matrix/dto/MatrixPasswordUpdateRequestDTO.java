package de.caritas.cob.userservice.api.adapters.matrix.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO for the Synapse admin password update request. Deliberately has no {@code toString} so
 * framework-level request logging (e.g. RestTemplate DEBUG "Writing [...]") can never expose the
 * password.
 */
@Getter
@Setter
public class MatrixPasswordUpdateRequestDTO {

  private String password;

  @JsonProperty("logout_devices")
  private boolean logoutDevices;
}
