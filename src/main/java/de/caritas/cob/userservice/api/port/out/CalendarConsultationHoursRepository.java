package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CalendarConsultationHours;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface CalendarConsultationHoursRepository
    extends CrudRepository<CalendarConsultationHours, UUID> {

  List<CalendarConsultationHours> findByConsultantIdOrderByWeekdayAscStartTimeAscEndTimeAsc(
      String consultantId);

  /** Removes every Sprechstunde of one counsellor, before the new ones are stored. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM CalendarConsultationHours h WHERE h.consultantId = :consultantId")
  int deleteByConsultantId(@Param("consultantId") String consultantId);
}
