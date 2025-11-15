package de.caritas.cob.userservice.api.adapters.matrix.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** DTO for Matrix room creation request. */
@Getter
@Setter
public class MatrixCreateRoomRequestDTO {

  private String name;

  @JsonProperty("room_alias_name")
  private String roomAliasName;

  private String preset;
  private String visibility;

  @JsonProperty("initial_state")
  private List<InitialStateEvent> initialState;

  @Getter
  @Setter
  public static class InitialStateEvent {
    private String type;
    private Object content;

    @JsonProperty("state_key")
    private String stateKey;
  }
}
