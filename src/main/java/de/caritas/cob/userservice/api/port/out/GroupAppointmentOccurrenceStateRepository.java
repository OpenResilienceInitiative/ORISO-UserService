package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.GroupAppointmentOccurrenceState;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface GroupAppointmentOccurrenceStateRepository
    extends CrudRepository<GroupAppointmentOccurrenceState, Long> {
  Optional<GroupAppointmentOccurrenceState> findBySeriesIdAndOccurrenceIndex(
      Long seriesId, int occurrenceIndex);

  List<GroupAppointmentOccurrenceState> findBySeriesId(Long seriesId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select state from GroupAppointmentOccurrenceState state "
          + "where state.seriesId = :seriesId and state.occurrenceIndex = :occurrenceIndex")
  Optional<GroupAppointmentOccurrenceState> findForUpdate(
      @Param("seriesId") Long seriesId, @Param("occurrenceIndex") int occurrenceIndex);
}
