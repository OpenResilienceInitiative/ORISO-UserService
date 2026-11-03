package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.EnquiryRejection;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EnquiryRejectionRepository extends JpaRepository<EnquiryRejection, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from EnquiryRejection r where r.sessionId=:id")
  Optional<EnquiryRejection> findForUpdate(@Param("id") Long id);

  @Query(
      "select r from EnquiryRejection r where r.state=:state and r.nextAttemptAt<=:now and (r.claimUntil is null or r.claimUntil<=:now) order by r.rejectedAt")
  List<EnquiryRejection> findDue(
      @Param("state") EnquiryRejection.State state, @Param("now") LocalDateTime now, Pageable page);
}
