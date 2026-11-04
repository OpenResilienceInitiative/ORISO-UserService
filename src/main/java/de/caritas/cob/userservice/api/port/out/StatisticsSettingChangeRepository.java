package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.StatisticsSettingChange;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface StatisticsSettingChangeRepository
    extends JpaRepository<StatisticsSettingChange, Long> {

  Optional<StatisticsSettingChange> findFirstBySettingKeyOrderByIdDesc(String settingKey);

  List<StatisticsSettingChange> findAllByOrderByIdAsc();

  /**
   * Retention: blanks admin id and name on lines that took effect strictly before the cutoff. The
   * line itself stays: the latest line per setting is its current value, the line count is the
   * revision the settings are saved against, and the rule history must stay readable.
   *
   * @return number of lines that lost their actor
   */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update StatisticsSettingChange c set c.changedById = null, c.changedByName = null "
          + "where c.effectiveFrom < :cutoff "
          + "and (c.changedById is not null or c.changedByName is not null)")
  int anonymizeActorBefore(@Param("cutoff") LocalDateTime cutoff);
}
