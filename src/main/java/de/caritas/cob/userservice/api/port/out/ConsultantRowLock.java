package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.Consultant;
import java.util.Optional;

/** Locks an active consultant visible to the current tenant and reads the current row state. */
public interface ConsultantRowLock {
  Optional<Consultant> findFreshActiveByIdForUpdate(String consultantId);
}
