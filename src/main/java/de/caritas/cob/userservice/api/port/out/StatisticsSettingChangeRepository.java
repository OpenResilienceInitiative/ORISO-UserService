package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.StatisticsSettingChange;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatisticsSettingChangeRepository
    extends JpaRepository<StatisticsSettingChange, Long> {

  Optional<StatisticsSettingChange> findFirstBySettingKeyOrderByIdDesc(String settingKey);

  List<StatisticsSettingChange> findAllByOrderByIdAsc();
}
