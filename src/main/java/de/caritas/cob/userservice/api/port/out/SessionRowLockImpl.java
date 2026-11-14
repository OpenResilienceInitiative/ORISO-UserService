package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.Session;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;

/** Spring Data fragment of {@link SessionRepository}. */
class SessionRowLockImpl implements SessionRowLock {
  @PersistenceContext private EntityManager entityManager;

  @Override
  public Optional<Session> lockFresh(Long sessionId) {
    return FreshRowLock.lock(entityManager, Session.class, sessionId);
  }
}
