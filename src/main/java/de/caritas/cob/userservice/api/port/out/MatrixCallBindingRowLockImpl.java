package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;

/** Spring Data fragment of {@link MatrixCallBindingRepository}. */
class MatrixCallBindingRowLockImpl implements MatrixCallBindingRowLock {
  @PersistenceContext private EntityManager entityManager;

  @Override
  public Optional<MatrixCallBinding> lockById(Long id) {
    return FreshRowLock.lock(entityManager, MatrixCallBinding.class, id);
  }
}
