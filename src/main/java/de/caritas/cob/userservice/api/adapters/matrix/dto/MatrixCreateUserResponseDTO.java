package de.caritas.cob.userservice.api.adapters.matrix.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/** DTO for Matrix user creation response. */
@Getter
@Setter
public class MatrixCreateUserResponseDTO {

  @JsonProperty("user_id")
  private String userId;
}
