package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import java.util.Optional;

/** Row lock of an invite link that always returns its latest committed state (FE#2209). */
public interface AgencyInviteLinkRowLock {
  /**
   * Locked: knocks through one share link are serialised on the link row (#1487). Returns the
   * latest committed state, even when this transaction already read an older copy of the link.
   */
  Optional<AgencyInviteLink> findByIdForUpdate(Long id);
}
