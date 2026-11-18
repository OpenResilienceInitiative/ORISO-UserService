package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomRetirement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgencyEnquiryRoomRetirementRepository
    extends JpaRepository<AgencyEnquiryRoomRetirement, Long> {

  boolean existsByRoomIdAndSessionIdAndAgencyIdAndTenantIdAndReplacementRoomId(
      String roomId, Long sessionId, Long agencyId, Long tenantId, String replacementRoomId);
}
