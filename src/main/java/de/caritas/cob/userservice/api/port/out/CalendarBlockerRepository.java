package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CalendarBlocker;
import de.caritas.cob.userservice.api.model.CalendarBlocker.Source;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface CalendarBlockerRepository extends CrudRepository<CalendarBlocker, UUID> {

  /** The blockers and busy times of one counsellor that overlap the window. */
  @Query(
      "SELECT b FROM CalendarBlocker b WHERE b.consultantId = :consultantId "
          + "AND b.startTime < :to AND b.endTime > :from ORDER BY b.startTime")
  List<CalendarBlocker> findOverlapping(
      @Param("consultantId") String consultantId,
      @Param("from") Instant from,
      @Param("to") Instant to);

  Optional<CalendarBlocker> findByIdAndConsultantIdAndSource(
      UUID id, String consultantId, Source source);

  /** Removes every blocker of one source, e.g. the imported busy times before a fresh import. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "DELETE FROM CalendarBlocker b WHERE b.consultantId = :consultantId AND b.source = :source")
  int deleteByConsultantIdAndSource(
      @Param("consultantId") String consultantId, @Param("source") Source source);

  /** Removes every blocker and busy time of a counsellor whose account is deleted. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM CalendarBlocker b WHERE b.consultantId = :consultantId")
  int deleteByConsultantId(@Param("consultantId") String consultantId);
}
