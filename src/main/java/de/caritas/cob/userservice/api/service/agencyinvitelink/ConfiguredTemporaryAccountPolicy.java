package de.caritas.cob.userservice.api.service.agencyinvitelink;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Stand-in until the platform and Träger switch for temporary accounts is exposed
 * (ORISO-Admin#1241, ORISO-UserService#1486); then a settings-backed implementation replaces this
 * bean.
 */
@Service
public class ConfiguredTemporaryAccountPolicy implements TemporaryAccountPolicy {

  private final boolean allowedByDefault;

  public ConfiguredTemporaryAccountPolicy(
      @Value("${call-share-links.temporary-accounts-allowed-by-default:true}")
          boolean allowedByDefault) {
    this.allowedByDefault = allowedByDefault;
  }

  @Override
  public boolean allows(Long tenantId, Long agencyId, InviteLinkChatType medium) {
    return allowedByDefault;
  }
}
