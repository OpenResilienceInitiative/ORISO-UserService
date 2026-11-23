package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.ConsultantMessageStat;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists and counts {@link ConsultantMessageStat} rows. Every row is keyed by a pseudonymous
 * {@code consultant_hmac}, never the plain consultant id (see {@link ConsultantMessageStat} for
 * why). The count query is native for consistency with the sibling {@code
 * ConsultantStatisticsRepository} aggregates and to keep the tenant predicate explicit.
 */
public interface ConsultantMessageStatRepository
    extends CrudRepository<ConsultantMessageStat, Long> {

  @Query(
      nativeQuery = true,
      value =
          "SELECT COUNT(*) FROM consultant_message_stat s "
              + "WHERE s.consultant_hmac = :consultantHmac AND s.tenant_id = :tenantId "
              + "AND s.sent_date >= :fromDate AND s.sent_date < :toDate")
  long countByConsultantHmacAndTenantIdInPeriod(
      @Param("consultantHmac") String consultantHmac,
      @Param("tenantId") Long tenantId,
      @Param("fromDate") LocalDateTime fromDate,
      @Param("toDate") LocalDateTime toDate);

  /**
   * Retention: deletes at most {@code batchSize} rows sent strictly before the cutoff. Batched so a
   * large backlog never holds one long lock on the table the live counting writes to.
   *
   * @return number of deleted rows
   */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      nativeQuery = true,
      value = "DELETE FROM consultant_message_stat WHERE sent_date < :cutoff LIMIT :batchSize")
  int deleteBatchSentBefore(
      @Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);

  /** Account deletion: removes every row of one pseudonymized consultant. */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from ConsultantMessageStat s where s.consultantHmac = :consultantHmac")
  int deleteByConsultantHmac(@Param("consultantHmac") String consultantHmac);
}
