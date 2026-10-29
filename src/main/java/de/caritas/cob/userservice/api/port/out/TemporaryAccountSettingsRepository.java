package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.TemporaryAccountSettings;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface TemporaryAccountSettingsRepository
    extends JpaRepository<TemporaryAccountSettings, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM TemporaryAccountSettings s WHERE s.id = ?1")
  Optional<TemporaryAccountSettings> findLockedById(Long id);
}
