package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CalendarWorkingHoursSetting;
import org.springframework.data.repository.CrudRepository;

public interface CalendarWorkingHoursSettingRepository
    extends CrudRepository<CalendarWorkingHoursSetting, String> {}
