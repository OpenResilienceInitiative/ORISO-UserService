package de.caritas.cob.userservice.api.adapters.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body for a consultant toggling their live-chat availability (Live Chat on/off). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsultantLiveAvailabilityRequestDTO {

  /** {@code true} when the consultant enables Live Chat; {@code false} on disable or logout. */
  private Boolean available;
}
