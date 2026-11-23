package de.caritas.cob.userservice.api.adapters.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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

  /**
   * #1704: {@code false} when the AVV of the consultant's provider blocks new counselling, so
   * advice seekers cannot start a live chat; {@code null} when unknown or not part of the response.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private Boolean newCounsellingAllowed;

  public ConsultantLiveAvailabilityResponseDTO(Boolean available) {
    this.available = available;
  }
}
