package de.caritas.cob.userservice.api.adapters.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeletionPauseRequestDTO {

  @NotBlank
  @Size(max = 512)
  private String reason;

  /** Optional; defaults to configured deletion.pause.defaultMonths. */
  private Integer months;
}
