package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CommunicationStat;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists {@link CommunicationStat} rows; the dashboard reads them via {@link
 * AdminStatisticsRepository}.
 */
public interface CommunicationStatRepository extends CrudRepository<CommunicationStat, Long> {

  boolean existsByEventKey(String eventKey);

  /**
   * Retention: deletes at most {@code batchSize} rows that occurred strictly before the cutoff.
   *
   * @return number of deleted rows
   */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      nativeQuery = true,
      value = "DELETE FROM communication_stat WHERE occurred_at < :cutoff LIMIT :batchSize")
  int deleteBatchOccurredBefore(
      @Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);
}
