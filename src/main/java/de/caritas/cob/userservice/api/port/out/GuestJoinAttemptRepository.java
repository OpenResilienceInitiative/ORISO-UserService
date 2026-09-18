package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.GuestJoinAttempt;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface GuestJoinAttemptRepository extends JpaRepository<GuestJoinAttempt, Long> {
  Optional<GuestJoinAttempt> findByKeyHash(String keyHash);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "2000"))
  @Query("SELECT attempt FROM GuestJoinAttempt attempt WHERE attempt.keyHash = :keyHash")
  Optional<GuestJoinAttempt> findByKeyHashForUpdate(@Param("keyHash") String keyHash);
}
