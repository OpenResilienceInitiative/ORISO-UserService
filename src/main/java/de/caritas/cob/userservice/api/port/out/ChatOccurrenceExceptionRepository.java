package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ChatOccurrenceException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;

public interface ChatOccurrenceExceptionRepository
    extends CrudRepository<ChatOccurrenceException, Long> {

  List<ChatOccurrenceException> findBySeries_Id(Long seriesId);

  /** The exceptions of several series in one read, e.g. for a calendar with many groups. */
  List<ChatOccurrenceException> findBySeries_IdIn(Collection<Long> seriesIds);

  Optional<ChatOccurrenceException> findBySeries_IdAndOriginalOccurrenceStartUtc(
      Long seriesId, LocalDateTime originalOccurrenceStartUtc);
}
