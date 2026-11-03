package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.service.agency.EffectiveAgencySettingsLookup;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/**
 * Reads the effective "Direkteinladung erlauben" switch (ORISO-Admin#1242) per chat type, as the
 * AgencyService serves it: Träger AND Beratungsstelle, a Träger "off" always wins. A link without a
 * Beratungsstelle (a call without a conversation) is judged by the Träger's own value.
 *
 * <p>Fail closed: a switch that cannot be read, is not served, or belongs to another medium or
 * Träger never allows a link. Nothing is cached, so a switch an admin turns off applies to the next
 * request, also for links created earlier. An unreachable AgencyService or TenantService is an
 * outage (503), not a "no".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EffectiveDirectInvitePolicy implements DirectInvitePolicy {

  private final @NonNull EffectiveAgencySettingsLookup effectiveAgencySettingsLookup;
  private final @NonNull TenantService tenantService;

  @Override
  public boolean allows(Long tenantId, Long agencyId, InviteLinkChatType medium) {
    if (tenantId == null || medium == null || !InviteLinkChatType.isDirectMedium(medium.name())) {
      return false;
    }
    // A guest opening a link has no tenant context of their own yet: ask in the link's Träger.
    var answer = new AtomicReference<Boolean>();
    try {
      TenantContext.runIn(
          tenantId,
          () ->
              answer.set(
                  agencyId == null
                      ? ofTenant(tenantId, medium)
                      : ofAgency(tenantId, agencyId, medium)));
    } catch (RestClientException unavailable) {
      log.warn(
          "Direct invitation switch for tenant {} could not be read: {}",
          tenantId,
          unavailable.getClass().getSimpleName());
      throw new ServiceUnavailableException("The direct invitation setting is not available");
    }
    return Boolean.TRUE.equals(answer.get());
  }

  private Boolean ofAgency(Long tenantId, Long agencyId, InviteLinkChatType medium) {
    return effectiveAgencySettingsLookup
        .findEffectiveSettings(agencyId, tenantId)
        .map(
            settings ->
                switch (medium) {
                  case CHAT -> settings.getFeatureDirectInviteChatEnabled();
                  case AUDIO -> settings.getFeatureDirectInviteAudioEnabled();
                  case VIDEO -> settings.getFeatureDirectInviteVideoEnabled();
                  case LIVE_CHAT -> null;
                })
        .orElse(null);
  }

  private Boolean ofTenant(Long tenantId, InviteLinkChatType medium) {
    var tenant = tenantService.getRestrictedTenantDataFresh(tenantId);
    if (tenant == null || tenant.getSettings() == null || !tenantId.equals(tenant.getId())) {
      return null;
    }
    var settings = tenant.getSettings();
    return switch (medium) {
      case CHAT -> settings.getFeatureDirectInviteChatEnabled();
      case AUDIO -> settings.getFeatureDirectInviteAudioEnabled();
      case VIDEO -> settings.getFeatureDirectInviteVideoEnabled();
      case LIVE_CHAT -> null;
    };
  }
}
