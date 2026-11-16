package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CalendarWorkingHoursSetting;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface CalendarWorkingHoursSettingRepository
    extends CrudRepository<CalendarWorkingHoursSetting, String> {

  /** Removes the holiday region of a counsellor whose account is deleted. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM CalendarWorkingHoursSetting s WHERE s.consultantId = :consultantId")
  int deleteByConsultantId(@Param("consultantId") String consultantId);
}
