package de.caritas.cob.userservice.api.adapters.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Topic-scoped live-chat consultant availability, used by the anonymous pre-registration screen to
 * decide whether to show the "no counsellor available" alert before an enquiry is created.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsultantAvailabilityDTO {

  /** {@code true} when at least one eligible consultant is available for the topic. */
  private boolean available;

  /** Number of eligible (assigned, not absent, online when known) consultants for the topic. */
  private int numAvailableConsultants;
}
