package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CalendarWorkingDayException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface CalendarWorkingDayExceptionRepository
    extends CrudRepository<CalendarWorkingDayException, UUID> {

  /**
   * Locks the counsellor's own row until the transaction ends, so that their exception writes run
   * one after another: two new dates cannot both pass the limit check, and one date cannot be
   * stored twice. Empty when there is no such counsellor.
   */
  @Query(
      value = "SELECT consultant_id FROM consultant WHERE consultant_id = :consultantId FOR UPDATE",
      nativeQuery = true)
  Optional<String> lockConsultant(@Param("consultantId") String consultantId);

  /** The rows of one counsellor from one date onwards. */
  List<CalendarWorkingDayException> findByConsultantIdAndExceptionDateGreaterThanEqual(
      String consultantId, LocalDate from);

  boolean existsByConsultantIdAndExceptionDate(String consultantId, LocalDate date);

  /** How many dates one counsellor keeps an exception for. */
  @Query(
      "SELECT COUNT(DISTINCT e.exceptionDate) FROM CalendarWorkingDayException e"
          + " WHERE e.consultantId = :consultantId")
  long countDatesByConsultantId(@Param("consultantId") String consultantId);

  /** Removes the exception of one counsellor on one date, before the new windows are stored. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "DELETE FROM CalendarWorkingDayException e"
          + " WHERE e.consultantId = :consultantId AND e.exceptionDate = :date")
  int deleteByConsultantIdAndExceptionDate(
      @Param("consultantId") String consultantId, @Param("date") LocalDate date);

  /** Removes one counsellor's exceptions on dates before the given one. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "DELETE FROM CalendarWorkingDayException e"
          + " WHERE e.consultantId = :consultantId AND e.exceptionDate < :before")
  int deleteByConsultantIdBefore(
      @Param("consultantId") String consultantId, @Param("before") LocalDate before);
}
