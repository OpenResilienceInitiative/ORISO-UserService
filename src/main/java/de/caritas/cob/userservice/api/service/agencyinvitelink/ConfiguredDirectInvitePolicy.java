package de.caritas.cob.userservice.api.service.agencyinvitelink;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Stand-in until ORISO-TenantService#331 exposes the effective DIRECT_INVITE_{CHAT,AUDIO,VIDEO}
 * value; then a TenantService-backed implementation replaces this bean.
 */
@Service
public class ConfiguredDirectInvitePolicy implements DirectInvitePolicy {

  private final boolean allowedByDefault;

  public ConfiguredDirectInvitePolicy(
      @Value("${direct-links.allowed-by-default:true}") boolean allowedByDefault) {
    this.allowedByDefault = allowedByDefault;
  }

  @Override
  public boolean allows(Long tenantId, Long agencyId, InviteLinkChatType medium) {
    return allowedByDefault;
  }
}
