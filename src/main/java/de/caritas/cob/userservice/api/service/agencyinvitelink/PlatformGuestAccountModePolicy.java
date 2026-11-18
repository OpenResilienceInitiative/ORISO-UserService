package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.model.GuestAccountMode;
import java.util.EnumSet;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * #1662: the platform cap. While temporary accounts are not allowed, only permanent accounts are
 * offered; otherwise every mode. Callers get it through {@link TraegerGuestAccountModePolicy},
 * which adds the Träger's allowed modes.
 */
@Service
@RequiredArgsConstructor
public class PlatformGuestAccountModePolicy implements GuestAccountModePolicy {

  private final @NonNull TemporaryAccountPolicy temporaryAccountPolicy;

  @Override
  public Set<GuestAccountMode> allowedModes(
      Long tenantId, Long agencyId, InviteLinkChatType medium) {
    return temporaryAccountPolicy.allows(tenantId, agencyId, medium)
        ? EnumSet.allOf(GuestAccountMode.class)
        : EnumSet.of(GuestAccountMode.PERMANENT);
  }
}
