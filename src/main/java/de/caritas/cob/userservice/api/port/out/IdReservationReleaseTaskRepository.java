package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.IdReservationReleaseTask;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface IdReservationReleaseTaskRepository
    extends JpaRepository<IdReservationReleaseTask, Long> {

  List<IdReservationReleaseTask> findTop100ByOrderByCreateDateAsc();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select task from IdReservationReleaseTask task where task.id = :id")
  Optional<IdReservationReleaseTask> findByIdForUpdate(Long id);
}
