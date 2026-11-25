package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomDecision;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AgencyEnquiryRoomDecisionRepository
    extends JpaRepository<AgencyEnquiryRoomDecision, Long> {

  List<AgencyEnquiryRoomDecision> findByPreparedUntilLessThanEqualOrderByPreparedUntilAscIdAsc(
      LocalDateTime now, Pageable page);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      SELECT d FROM AgencyEnquiryRoomDecision d
      WHERE d.sessionId = :sessionId AND d.ownershipRevision = :ownershipRevision
      """)
  Optional<AgencyEnquiryRoomDecision> lockDecision(Long sessionId, long ownershipRevision);

  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query(
      """
      SELECT d FROM AgencyEnquiryRoomDecision d
      WHERE d.tenantId = :tenantId AND d.agencyId = :agencyId
        AND (d.outcome = de.caritas.cob.userservice.api.model.AgencyEnquiryRoomDecision.Outcome.PENDING
          OR (d.resolvedOrder > :departureOrder
            AND d.outcome IN (
              de.caritas.cob.userservice.api.model.AgencyEnquiryRoomDecision.Outcome.REPLACED,
              de.caritas.cob.userservice.api.model.AgencyEnquiryRoomDecision.Outcome.REOPENED)))
      ORDER BY d.id
      """)
  List<AgencyEnquiryRoomDecision> lockAffectedDecisions(
      Long tenantId, Long agencyId, long departureOrder);
}
