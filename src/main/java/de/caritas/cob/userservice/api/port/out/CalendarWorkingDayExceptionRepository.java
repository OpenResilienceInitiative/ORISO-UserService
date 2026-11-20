package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CalendarWorkingDayException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface CalendarWorkingDayExceptionRepository
    extends CrudRepository<CalendarWorkingDayException, UUID> {

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

  /** Removes every exception of a counsellor whose account is deleted. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM CalendarWorkingDayException e WHERE e.consultantId = :consultantId")
  int deleteByConsultantId(@Param("consultantId") String consultantId);
}
