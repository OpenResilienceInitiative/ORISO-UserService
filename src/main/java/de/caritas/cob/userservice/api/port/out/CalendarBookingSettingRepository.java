package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CalendarBookingSetting;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface CalendarBookingSettingRepository
    extends CrudRepository<CalendarBookingSetting, String> {

  /** Removes the booking settings of a counsellor whose account is deleted. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM CalendarBookingSetting s WHERE s.consultantId = :consultantId")
  int deleteByConsultantId(@Param("consultantId") String consultantId);
}
