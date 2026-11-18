package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.CalendarAutoAccept;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface CalendarAutoAcceptRepository extends CrudRepository<CalendarAutoAccept, UUID> {

  List<CalendarAutoAccept> findByConsultantId(String consultantId);

  boolean existsByConsultantIdAndColleagueId(String consultantId, String colleagueId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM CalendarAutoAccept a WHERE a.consultantId = :consultantId")
  int deleteByConsultantId(@Param("consultantId") String consultantId);

  /** Removes a deleted counsellor's list and every list that names them. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "DELETE FROM CalendarAutoAccept a "
          + "WHERE a.consultantId = :consultantId OR a.colleagueId = :consultantId")
  int deleteByConsultantOrColleague(@Param("consultantId") String consultantId);
}
