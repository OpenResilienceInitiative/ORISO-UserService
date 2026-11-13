package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.Session;
import java.util.Optional;

/** Row lock of a session that always returns its latest committed state (FE#2209 round 5). */
public interface SessionRowLock {
  /**
   * Locked with {@code PESSIMISTIC_WRITE}. Returns the latest committed state, even when this
   * transaction already read an older copy of the session (for example its assigned counsellor
   * before a reassignment committed), see {@link FreshRowLock}.
   */
  Optional<Session> lockFresh(Long sessionId);
}
