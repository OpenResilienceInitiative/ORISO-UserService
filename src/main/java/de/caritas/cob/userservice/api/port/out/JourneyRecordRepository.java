package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.JourneyRecord;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JourneyRecordRepository extends JpaRepository<JourneyRecord, JourneyRecord.Key> {

  List<JourneyRecord> findByScopeAndScopeIdOrderByJourneyId(String scope, Long scopeId);

  List<JourneyRecord> findByJourneyId(String journeyId);

  /** Takes the write lock on one row until the transaction ends ({@code SELECT … FOR UPDATE}). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select r from JourneyRecord r where r.scope = :scope and r.scopeId = :scopeId"
          + " and r.journeyId = :journeyId")
  Optional<JourneyRecord> lockRow(
      @Param("scope") String scope,
      @Param("scopeId") Long scopeId,
      @Param("journeyId") String journeyId);

  /** What changed, without reading the bodies: id, version and last change of each row. */
  @Query(
      "select r.journeyId, r.version, r.updateDate from JourneyRecord r"
          + " where r.scope = :scope and r.scopeId = :scopeId order by r.journeyId")
  List<Object[]> versionsOf(@Param("scope") String scope, @Param("scopeId") Long scopeId);
}
