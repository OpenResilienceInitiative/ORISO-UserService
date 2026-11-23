package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomAttempt;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgencyEnquiryRoomAttemptRepository
    extends JpaRepository<AgencyEnquiryRoomAttempt, Long> {

  List<AgencyEnquiryRoomAttempt> findAllBySessionIdAndRoomId(Long sessionId, String roomId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<AgencyEnquiryRoomAttempt> findAllBySessionIdAndRequesterJoinPlanIsNotNullOrderByIdAsc(
      Long sessionId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select attempt from AgencyEnquiryRoomAttempt attempt where attempt.id = :id")
  Optional<AgencyEnquiryRoomAttempt> lockAttempt(@Param("id") Long id);
}
