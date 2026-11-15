package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CalendarSync;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface CalendarSyncRepository extends CrudRepository<CalendarSync, String> {

  Optional<CalendarSync> findByFeedId(UUID feedId);

  List<CalendarSync> findByImportUrlIsNotNull();

  /**
   * Locked, so that a transaction that reads the row and writes it back cannot overwrite what
   * another one changed in between, e.g. an import restoring the address it was started with.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM CalendarSync s WHERE s.consultantId = :consultantId")
  Optional<CalendarSync> findForUpdate(@Param("consultantId") String consultantId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE CalendarSync s SET s.feedLastFetchedAt = :at WHERE s.consultantId = :consultantId")
  int markFeedFetched(@Param("consultantId") String consultantId, @Param("at") Instant at);

  /** Removes the connections of a counsellor whose account is deleted. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM CalendarSync s WHERE s.consultantId = :consultantId")
  int deleteByConsultantId(@Param("consultantId") String consultantId);
}
