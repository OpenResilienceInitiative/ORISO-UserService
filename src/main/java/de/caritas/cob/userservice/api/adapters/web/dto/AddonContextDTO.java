package de.caritas.cob.userservice.api.adapters.web.dto;

import java.util.List;

/** What an add-on may know about a user of the host (contract oriso-addon/v1, ADR-034). */
public record AddonContextDTO(
    String userId,
    String type,
    List<Long> agencyIds,
    List<SessionRef> sessions,
    List<ConsultantRef> consultants) {

  public record SessionRef(Long id, Long agencyId, String consultantId, String status) {}

  public record ConsultantRef(String id, String username, String displayName) {}
}
