package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CalendarConsultationOnce;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface CalendarConsultationOnceRepository
    extends CrudRepository<CalendarConsultationOnce, UUID> {

  /** The one-offs of one counsellor whose end lies after the instant, by start. */
  List<CalendarConsultationOnce> findByConsultantIdAndEndAtAfterOrderByStartAtAsc(
      String consultantId, Instant after);

  /** The one-offs of one counsellor that overlap the window [from, to), by start. */
  @Query(
      "SELECT o FROM CalendarConsultationOnce o WHERE o.consultantId = :consultantId "
          + "AND o.startAt < :to AND o.endAt > :from ORDER BY o.startAt")
  List<CalendarConsultationOnce> findOverlapping(
      @Param("consultantId") String consultantId,
      @Param("from") Instant from,
      @Param("to") Instant to);

  Optional<CalendarConsultationOnce> findByIdAndConsultantId(UUID id, String consultantId);

  /**
   * Removes the blockers every one-off of a counsellor was released over; before {@link
   * #deleteByConsultantId}, which as a bulk delete does not reach them.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "DELETE FROM calendar_consultation_once_blocker WHERE once_id IN "
              + "(SELECT id FROM calendar_consultation_once WHERE consultant_id = :consultantId)",
      nativeQuery = true)
  int deleteReleasedOverByConsultantId(@Param("consultantId") String consultantId);

  /**
   * Removes every one-off of a counsellor whose account is deleted; {@link
   * #deleteReleasedOverByConsultantId} first.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM CalendarConsultationOnce o WHERE o.consultantId = :consultantId")
  int deleteByConsultantId(@Param("consultantId") String consultantId);
}
