package de.caritas.cob.userservice.api.adapters.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response body reporting whether the calling consultant is currently available for live chat, read
 * from the authoritative {@code ConsultantActivityRegistry} (ADR-007).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsultantLiveAvailabilityResponseDTO {

  /** {@code true} when the consultant is within the live-chat availability window. */
  private Boolean available;
}
