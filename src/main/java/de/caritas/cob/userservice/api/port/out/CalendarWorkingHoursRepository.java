package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CalendarWorkingHours;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface CalendarWorkingHoursRepository extends CrudRepository<CalendarWorkingHours, UUID> {

  List<CalendarWorkingHours> findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(
      String consultantId);

  /** Removes every window of one counsellor, before the new ones are stored. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM CalendarWorkingHours h WHERE h.consultantId = :consultantId")
  int deleteByConsultantId(@Param("consultantId") String consultantId);
}
