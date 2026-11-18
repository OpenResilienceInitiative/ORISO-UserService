package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CalendarColleagueAppointment;
import de.caritas.cob.userservice.api.model.CalendarColleagueAppointment.Status;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface CalendarColleagueAppointmentRepository
    extends CrudRepository<CalendarColleagueAppointment, UUID> {

  /** Appointments with a colleague that one counsellor is part of, overlapping the window. */
  @Query(
      "SELECT a FROM CalendarColleagueAppointment a "
          + "WHERE (a.organizerId = :consultantId OR a.inviteeId = :consultantId) "
          + "AND a.status IN :statuses AND a.startTime < :to AND a.endTime > :from "
          + "ORDER BY a.startTime")
  List<CalendarColleagueAppointment> findOverlapping(
      @Param("consultantId") String consultantId,
      @Param("statuses") Collection<Status> statuses,
      @Param("from") Instant from,
      @Param("to") Instant to);

  /** Locked: an answer and a cancellation of the same appointment must not cross. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM CalendarColleagueAppointment a WHERE a.id = :id")
  Optional<CalendarColleagueAppointment> findByIdForUpdate(@Param("id") UUID id);

  /** Removes every appointment a deleted counsellor was part of, on either side. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "DELETE FROM CalendarColleagueAppointment a "
          + "WHERE a.organizerId = :consultantId OR a.inviteeId = :consultantId")
  int deleteByConsultantId(@Param("consultantId") String consultantId);
}
