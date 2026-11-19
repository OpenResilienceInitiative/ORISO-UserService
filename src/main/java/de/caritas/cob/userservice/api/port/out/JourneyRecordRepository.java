package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.JourneyRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JourneyRecordRepository extends JpaRepository<JourneyRecord, JourneyRecord.Key> {

  List<JourneyRecord> findByScopeAndScopeIdOrderByJourneyId(String scope, Long scopeId);

  List<JourneyRecord> findByJourneyId(String journeyId);
}
