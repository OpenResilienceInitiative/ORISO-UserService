package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;

/** Spring Data fragment of {@link AgencyInviteLinkRepository}. */
class AgencyInviteLinkRowLockImpl implements AgencyInviteLinkRowLock {
  @PersistenceContext private EntityManager entityManager;

  @Override
  public Optional<AgencyInviteLink> findByIdForUpdate(Long id) {
    return FreshRowLock.lock(entityManager, AgencyInviteLink.class, id);
  }
}
