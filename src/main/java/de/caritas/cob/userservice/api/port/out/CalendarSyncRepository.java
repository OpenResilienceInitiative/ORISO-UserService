package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CalendarSync;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface CalendarSyncRepository extends CrudRepository<CalendarSync, String> {

  Optional<CalendarSync> findByFeedId(UUID feedId);

  List<CalendarSync> findByImportUrlIsNotNull();

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE CalendarSync s SET s.feedLastFetchedAt = :at WHERE s.consultantId = :consultantId")
  int markFeedFetched(@Param("consultantId") String consultantId, @Param("at") Instant at);
}
