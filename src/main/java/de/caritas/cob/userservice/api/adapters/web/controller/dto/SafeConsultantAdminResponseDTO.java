package de.caritas.cob.userservice.api.adapters.web.controller.dto;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;

/** Preserves HAL serialization while protecting framework response diagnostics. */
public final class SafeConsultantAdminResponseDTO extends ConsultantAdminResponseDTO {
  public static SafeConsultantAdminResponseDTO from(ConsultantAdminResponseDTO original) {
    var safe = new SafeConsultantAdminResponseDTO();
    safe.setEmbedded(original.getEmbedded());
    safe.setLinks(original.getLinks());
    return safe;
  }

  @Override
  public String toString() {
    return "[REDACTED consultant creation response]";
  }
}
