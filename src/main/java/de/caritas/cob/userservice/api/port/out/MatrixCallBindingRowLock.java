package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import java.util.Optional;

/** Row lock of a call binding that always returns its latest committed state (FE#2209). */
public interface MatrixCallBindingRowLock {
  /**
   * Locks the binding row by primary key ({@code FOR UPDATE}) and returns its latest committed
   * state, even when this transaction already holds an older copy of it. Lock order: a binding is
   * locked before its session, observation and end rows, share link and knocks.
   */
  Optional<MatrixCallBinding> lockById(Long id);
}
