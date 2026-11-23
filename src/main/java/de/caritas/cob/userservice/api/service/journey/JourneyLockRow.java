package de.caritas.cob.userservice.api.service.journey;

import static de.caritas.cob.userservice.api.model.JourneyRecord.PLATFORM_SCOPE_ID;

import de.caritas.cob.userservice.api.model.JourneyRecord;
import de.caritas.cob.userservice.api.port.out.JourneyRecordRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes the row every journey write locks, where the database has none (one built without
 * Liquibase). It runs in a transaction of its own, so a concurrent writer making it as well only
 * fails this small transaction, never the save that needs the lock.
 */
@Component
@RequiredArgsConstructor
public class JourneyLockRow {

  private final @NonNull JourneyRecordRepository repository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void make() {
    var row = new JourneyRecord();
    row.setScope(JourneyRecord.LOCK);
    row.setScopeId(PLATFORM_SCOPE_ID);
    row.setJourneyId(JourneyRecord.LOCK_ID);
    row.setConversationType("-");
    row.setBody("{}");
    repository.saveAndFlush(row);
  }

  /** Makes the row unless someone else already did. */
  public void ensure() {
    try {
      make();
    } catch (DataIntegrityViolationException alreadyMade) {
      // Someone else made it first.
    }
  }
}
