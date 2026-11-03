package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.StatisticsSettingsLock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StatisticsSettingsLockRepository
    extends JpaRepository<StatisticsSettingsLock, Long> {

  /** Blocks until a save holding the lock has committed or rolled back. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT l FROM StatisticsSettingsLock l WHERE l.id = :id")
  Optional<StatisticsSettingsLock> findLockedById(@Param("id") Long id);
}
