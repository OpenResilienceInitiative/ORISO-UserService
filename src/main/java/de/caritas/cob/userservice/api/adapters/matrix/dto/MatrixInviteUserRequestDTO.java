package de.caritas.cob.userservice.api.adapters.matrix.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/** DTO for Matrix invite user request. */
@Getter
@Setter
public class MatrixInviteUserRequestDTO {

  @JsonProperty("user_id")
  private String userId;
}
