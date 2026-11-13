package de.caritas.cob.userservice.api.port.out;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.persister.entity.AbstractEntityPersister;

/**
 * FE#2209 round 4: locks a row and returns its latest committed state.
 *
 * <p>A plain {@code @Lock} query, or {@code find} with a lock, is not enough at MariaDB's
 * REPEATABLE READ: when the transaction read the row before (unlocked), Hibernate only upgrades the
 * lock ({@code select id ... for update}) and hands back the managed entity with its old state; a
 * later {@code refresh} of an entity that already holds the lock re-reads without {@code for
 * update}, from the transaction's snapshot. So:
 *
 * <ul>
 *   <li>not managed yet: {@code find} with the lock, one locking read that loads the latest row;
 *   <li>managed without the lock: first a locking existence read ({@code select 1 ... for update},
 *       CodeRabbit 4563094647), then {@code refresh} with the lock, a locking re-read of the latest
 *       row. A row deleted meanwhile is detached and answers empty; {@code refresh} never runs on
 *       it, so no {@code EntityNotFoundException} marks the transaction rollback-only;
 *   <li>managed and already write-locked by this transaction: it was read by a locking read of this
 *       helper, and nobody could change it since.
 * </ul>
 *
 * Bulk {@code @Lock} queries over the same rows must therefore run before any unlocked read of them
 * in a transaction (as admit-all does: it locks the call bindings first).
 */
final class FreshRowLock {
  private FreshRowLock() {}

  static <T> Optional<T> lock(EntityManager entityManager, Class<T> type, Object id) {
    if (id == null) return Optional.empty();
    var session = entityManager.unwrap(SessionImplementor.class);
    var key =
        session.generateEntityKey(
            id, session.getFactory().getMappingMetamodel().getEntityDescriptor(type));
    T managed = type.cast(session.getPersistenceContextInternal().getEntity(key));
    if (managed == null) {
      return Optional.ofNullable(entityManager.find(type, id, LockModeType.PESSIMISTIC_WRITE));
    }
    if (entityManager.getLockMode(managed) == LockModeType.PESSIMISTIC_WRITE) {
      return Optional.of(managed);
    }
    if (!existsLocked(entityManager, session, type, id)) {
      entityManager.detach(managed);
      return Optional.empty();
    }
    entityManager.refresh(managed, LockModeType.PESSIMISTIC_WRITE);
    return Optional.of(managed);
  }

  /** A locking read of the latest committed row: it waits for a holder and sees a delete. */
  private static boolean existsLocked(
      EntityManager entityManager, SessionImplementor session, Class<?> type, Object id) {
    var persister =
        (AbstractEntityPersister)
            session.getFactory().getMappingMetamodel().getEntityDescriptor(type);
    String sql =
        "SELECT 1 FROM "
            + persister.getTableName()
            + " WHERE "
            + persister.getIdentifierColumnNames()[0]
            + " = ? FOR UPDATE";
    return !entityManager
        .createNativeQuery(sql)
        .setParameter(1, id)
        .setFlushMode(FlushModeType.COMMIT)
        .getResultList()
        .isEmpty();
  }
}
