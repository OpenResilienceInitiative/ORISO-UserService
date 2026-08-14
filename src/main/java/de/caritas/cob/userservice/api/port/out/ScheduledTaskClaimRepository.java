package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ScheduledTaskClaim;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduledTaskClaimRepository extends JpaRepository<ScheduledTaskClaim, String> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT claim FROM ScheduledTaskClaim claim WHERE claim.taskName = :taskName")
  Optional<ScheduledTaskClaim> findByTaskNameForUpdate(@Param("taskName") String taskName);
}
