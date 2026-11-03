package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.service.agency.EffectiveAgencySettingsLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/** Enforces the effective per-medium invitation permission (ADR-029 §6). */
@Service
@RequiredArgsConstructor
public class ConfiguredDirectInvitePolicy implements DirectInvitePolicy {

  private final TenantService tenants;
  private final EffectiveAgencySettingsLookup agencySettings;

  @Override
  public boolean allows(Long tenantId, Long agencyId, InviteLinkChatType medium) {
    if (tenantId == null
        || tenantId <= 0
        || medium == null
        || medium == InviteLinkChatType.LIVE_CHAT) {
      return false;
    }
    try {
      // AgencyService may serve agency-only settings during a failed tenant lookup. Verify the
      // tenant authority separately, so that such a projection cannot bypass a Träger denial.
      var tenant = tenants.getRestrictedTenantDataFresh(tenantId);
      if (tenant == null || !tenantId.equals(tenant.getId()) || tenant.getSettings() == null) {
        return false;
      }
      var settings = tenant.getSettings();
      boolean tenantAllowed =
          Boolean.TRUE.equals(
              switch (medium) {
                case CHAT -> settings.getFeatureDirectInviteChatEnabled();
                case AUDIO -> settings.getFeatureDirectInviteAudioEnabled();
                case VIDEO -> settings.getFeatureDirectInviteVideoEnabled();
                case LIVE_CHAT -> false;
              });
      if (!tenantAllowed || agencyId == null) {
        return tenantAllowed;
      }
      return agencySettings
          .findEffectiveSettings(agencyId, tenantId)
          .map(
              agency ->
                  Boolean.TRUE.equals(
                      switch (medium) {
                        case CHAT -> agency.getFeatureDirectInviteChatEnabled();
                        case AUDIO -> agency.getFeatureDirectInviteAudioEnabled();
                        case VIDEO -> agency.getFeatureDirectInviteVideoEnabled();
                        case LIVE_CHAT -> false;
                      }))
          .orElse(false);
    } catch (RestClientException unavailable) {
      throw new ServiceUnavailableException("Direct invitation policy is unavailable");
    }
  }
}
