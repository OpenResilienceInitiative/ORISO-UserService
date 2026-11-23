package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CalendarBookingSetting;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface CalendarBookingSettingRepository
    extends CrudRepository<CalendarBookingSetting, String> {

  /**
   * The counsellor's booking settings read with a shared lock: a locking read sees what was
   * committed after the transaction's snapshot, so a booking follows the setting in force now.
   */
  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query("SELECT s FROM CalendarBookingSetting s WHERE s.consultantId = :consultantId")
  Optional<CalendarBookingSetting> lockById(@Param("consultantId") String consultantId);

  /** Removes the booking settings of a counsellor whose account is deleted. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM CalendarBookingSetting s WHERE s.consultantId = :consultantId")
  int deleteByConsultantId(@Param("consultantId") String consultantId);
}
