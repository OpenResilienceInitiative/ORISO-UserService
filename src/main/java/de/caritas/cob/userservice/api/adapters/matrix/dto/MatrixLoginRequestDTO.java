package de.caritas.cob.userservice.api.adapters.matrix.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO for Matrix password login requests. Deliberately has no {@code toString} so framework-level
 * request logging (e.g. RestTemplate DEBUG "Writing [...]") can never expose the password.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MatrixLoginRequestDTO {

  private String type;
  private String user;
  private String password;

  @JsonProperty("device_id")
  private String deviceId;

  @JsonProperty("initial_device_display_name")
  private String initialDeviceDisplayName;
}
