package de.caritas.cob.userservice.api.service.chat;

import java.util.Collection;

/**
 * Serialises "find or create" of the members-only room for one set of people (ORISO-UserService
 * #1418): of two simultaneous requests for the same people the second one waits and then finds the
 * first one's room instead of creating a duplicate.
 */
public interface MemberSetLock {

  /**
   * Waits for the lock of this set of people (the order of the ids does not matter).
   *
   * @throws
   *     de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException
   *     503 {@code INTERNAL_CHAT_BUSY} when the lock cannot be had in time
   */
  Held acquire(Collection<String> memberIds);

  /** The lock while it is held; release it with {@link #close()}, on the acquiring thread. */
  interface Held extends AutoCloseable {
    @Override
    void close();
  }
}
