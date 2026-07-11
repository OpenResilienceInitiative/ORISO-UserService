package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.Session;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read-only aggregate queries for the consultant profile statistics.
 *
 * <p>All statistics are computed from the application-layer database only (KDG compliance) and are
 * strictly scoped to a single consultant id; callers must pass the id of the authenticated
 * consultant, never a client-supplied one.
 */
public interface ConsultantStatisticsRepository extends Repository<Session, Long> {

  /**
   * Sessions currently assigned to the consultant that were created within the period. The app
   * layer does not record assignment timestamps, so the session creation date serves as the period
   * anchor.
   */
  @Query(
      nativeQuery = true,
      value =
          "SELECT COUNT(*) FROM session s "
              + "WHERE s.consultant_id = :consultantId "
              + "AND s.create_date >= :fromDate AND s.create_date < :toDate")
  long countAssignedSessionsCreatedInPeriod(
      @Param("consultantId") String consultantId,
      @Param("fromDate") LocalDateTime fromDate,
      @Param("toDate") LocalDateTime toDate);

  /**
   * Sessions assigned to the consultant that are in progress and were touched within the period.
   */
  @Query(
      nativeQuery = true,
      value =
          "SELECT COUNT(*) FROM session s "
              + "WHERE s.consultant_id = :consultantId AND s.status = 2 "
              + "AND s.update_date >= :fromDate AND s.update_date < :toDate")
  long countActiveSessionsInPeriod(
      @Param("consultantId") String consultantId,
      @Param("fromDate") LocalDateTime fromDate,
      @Param("toDate") LocalDateTime toDate);
}
