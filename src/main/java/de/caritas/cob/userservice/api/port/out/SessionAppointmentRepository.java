package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.SessionAppointment;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface SessionAppointmentRepository extends CrudRepository<SessionAppointment, UUID> {

  @Query(
      "SELECT a FROM SessionAppointment a WHERE a.session.id = :sessionId ORDER BY a.startTime DESC")
  List<SessionAppointment> findNewestOfSession(
      @Param("sessionId") Long sessionId, Pageable pageable);

  /** Locked, so that a cancellation and a reschedule of one appointment cannot overwrite. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM SessionAppointment a WHERE a.id = :id AND a.session.id = :sessionId")
  Optional<SessionAppointment> findOfSessionForUpdate(
      @Param("id") UUID id, @Param("sessionId") Long sessionId);

  @Query(
      "SELECT a FROM SessionAppointment a JOIN FETCH a.session s "
          + "WHERE (s.consultant.id = :consultantId OR s.id IN :coAccessSessionIds) "
          + "AND a.status = 'SCHEDULED' AND a.endTime > :now "
          + "ORDER BY a.startTime")
  List<SessionAppointment> findUpcomingOfConsultant(
      @Param("consultantId") String consultantId,
      @Param("coAccessSessionIds") Collection<Long> coAccessSessionIds,
      @Param("now") Instant now,
      Pageable pageable);

  @Query(
      "SELECT a FROM SessionAppointment a JOIN FETCH a.session s "
          + "WHERE s.user.userId = :userId AND a.status = 'SCHEDULED' AND a.endTime > :now "
          + "ORDER BY a.startTime")
  List<SessionAppointment> findUpcomingOfAsker(
      @Param("userId") String userId, @Param("now") Instant now, Pageable pageable);
}
