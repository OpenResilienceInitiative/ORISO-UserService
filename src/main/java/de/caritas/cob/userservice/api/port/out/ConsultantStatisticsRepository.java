package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read-only aggregate queries for the consultant profile statistics.
 *
 * <p>All statistics are computed from the application-layer database only (KDG compliance) and are
 * strictly scoped to a single consultant id; callers must pass the id of the authenticated
 * consultant, never a client-supplied one. Native queries are used because Hibernate tenant filters
 * do not apply to aggregations (see {@code AdminStatisticsRepository}); therefore every query also
 * carries an explicit tenant restriction.
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
              + "WHERE s.consultant_id = :consultantId AND s.tenant_id = :tenantId "
              + "AND s.create_date >= :fromDate AND s.create_date < :toDate")
  long countAssignedSessionsCreatedInPeriod(
      @Param("consultantId") String consultantId,
      @Param("tenantId") Long tenantId,
      @Param("fromDate") LocalDateTime fromDate,
      @Param("toDate") LocalDateTime toDate);

  /**
   * Sessions assigned to the consultant that are in progress and were touched within the period.
   */
  @Query(
      nativeQuery = true,
      value =
          "SELECT COUNT(*) FROM session s "
              + "WHERE s.consultant_id = :consultantId AND s.tenant_id = :tenantId "
              + "AND s.status = 2 AND s.update_date >= :fromDate AND s.update_date < :toDate")
  long countActiveSessionsInPeriod(
      @Param("consultantId") String consultantId,
      @Param("tenantId") Long tenantId,
      @Param("fromDate") LocalDateTime fromDate,
      @Param("toDate") LocalDateTime toDate);

  /** Number of appointments of one kind; {@code kind} is null on rows made before kinds existed. */
  interface AppointmentKindCount {
    SessionAppointment.Kind getKind();

    long getTotal();
  }

  /**
   * Scheduled (not cancelled) 1:1 appointments of the consultant's own sessions whose start lies in
   * {@code [from, to)}, counted per kind (ORISO-Frontend#2299). Callers cap {@code to} at now to
   * count only realised appointments.
   */
  @Query(
      "SELECT a.kind AS kind, COUNT(a) AS total FROM SessionAppointment a JOIN a.session s "
          + "WHERE s.consultant.id = :consultantId AND s.tenantId = :tenantId "
          + "AND a.status = 'SCHEDULED' AND a.startTime >= :from AND a.startTime < :to "
          + "GROUP BY a.kind")
  List<AppointmentKindCount> countScheduledAppointmentsByKindStartingIn(
      @Param("consultantId") String consultantId,
      @Param("tenantId") Long tenantId,
      @Param("from") Instant from,
      @Param("to") Instant to);

  /**
   * Cancelled 1:1 appointments of the consultant's own sessions that would have started in the
   * period.
   */
  @Query(
      "SELECT COUNT(a) FROM SessionAppointment a JOIN a.session s "
          + "WHERE s.consultant.id = :consultantId AND s.tenantId = :tenantId "
          + "AND a.status = 'CANCELLED' AND a.startTime >= :from AND a.startTime < :to")
  long countCancelledAppointmentsStartingIn(
      @Param("consultantId") String consultantId,
      @Param("tenantId") Long tenantId,
      @Param("from") Instant from,
      @Param("to") Instant to);
}
