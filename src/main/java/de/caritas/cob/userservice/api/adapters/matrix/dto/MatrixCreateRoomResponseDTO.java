package de.caritas.cob.userservice.api.adapters.matrix.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/** DTO for Matrix room creation response. */
@Getter
@Setter
public class MatrixCreateRoomResponseDTO {

  @JsonProperty("room_id")
  private String roomId;
}
