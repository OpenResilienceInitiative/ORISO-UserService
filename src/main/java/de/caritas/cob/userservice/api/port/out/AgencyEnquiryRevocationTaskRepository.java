package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.AgencyEnquiryRevocationTask;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AgencyEnquiryRevocationTaskRepository
    extends JpaRepository<AgencyEnquiryRevocationTask, Long> {

  @Query(
      """
      SELECT DISTINCT t.sessionId FROM AgencyEnquiryRevocationTask t
      WHERE t.consultantAgencyId = :relationId
      """)
  List<Long> pendingSessionIds(Long relationId);

  @Query(
      """
      SELECT t.sessionId AS sessionId, t.roomId AS roomId FROM AgencyEnquiryRevocationTask t
      WHERE t.consultantAgencyId = :relationId
      """)
  List<PendingRoomBinding> pendingRoomBindings(Long relationId);

  interface PendingRoomBinding {
    Long getSessionId();

    String getRoomId();
  }

  @Query(
      """
      SELECT t.id FROM AgencyEnquiryRevocationTask t
      WHERE t.retryAt <= :now ORDER BY t.retryAt, t.id
      """)
  List<Long> dueIds(LocalDateTime now, Pageable limit);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT t FROM AgencyEnquiryRevocationTask t WHERE t.id = :id")
  Optional<AgencyEnquiryRevocationTask> findByIdForUpdate(Long id);
}
