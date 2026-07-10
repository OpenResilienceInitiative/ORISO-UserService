package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ChatOccurrenceException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;

public interface ChatOccurrenceExceptionRepository
    extends CrudRepository<ChatOccurrenceException, Long> {

  List<ChatOccurrenceException> findBySeries_Id(Long seriesId);

  Optional<ChatOccurrenceException> findBySeries_IdAndOriginalOccurrenceStartUtc(
      Long seriesId, LocalDateTime originalOccurrenceStartUtc);
}
