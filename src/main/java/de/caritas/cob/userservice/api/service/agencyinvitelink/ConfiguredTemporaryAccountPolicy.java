package de.caritas.cob.userservice.api.service.agencyinvitelink;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Platform cap for temporary accounts. Link access also requires the effective medium permission.
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
