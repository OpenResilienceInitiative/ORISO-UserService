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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Reads the effective "Direkteinladung erlauben" switch (ORISO-Admin#1242) per chat type. The
 * AgencyService serves Träger AND Beratungsstelle, a Träger "off" wins. That answer can lag: the
 * AgencyService caches tenant data (ten minutes, no eviction) and serves the Beratungsstelle's own
 * value when its TenantService read fails. So the Träger's value is also read fresh from the
 * TenantService here and both must say yes. A link without a Beratungsstelle (a call without a
 * conversation) is judged by the Träger's value alone.
 *
 * <p>Fail closed: a switch that cannot be read, is not served, or belongs to another medium or
 * Träger never allows a link. This service caches nothing, so an admin's change applies to the next
 * request, also for links created earlier, as far as the AgencyService's own cache allows for the
 * Beratungsstelle value. An unreachable AgencyService or TenantService (timeout, 5xx, 408, 429,
 * unreadable answer) is an outage (503), not a "no". Any other 4xx from a provider denies the link.
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
    } catch (HttpClientErrorException refused) {
      if (isTransient(refused)) {
        throw unavailable(tenantId, refused);
      }
      // The provider understood the question and said no (for example 400): a refusal of the
      // link, not an outage. The caller answers 403 or 410, never a 500.
      log.warn(
          "Direct invitation switch for tenant {} was refused by its provider with {}",
          tenantId,
          refused.getStatusCode().value());
      return false;
    } catch (RestClientException failure) {
      throw unavailable(tenantId, failure);
    }
    return Boolean.TRUE.equals(answer.get());
  }

  /** Timeouts, 5xx and unreadable answers are caught as outages; of the 4xx only these two are. */
  private static boolean isTransient(HttpClientErrorException refused) {
    return refused.getStatusCode().value() == 408 || refused.getStatusCode().value() == 429;
  }

  private ServiceUnavailableException unavailable(Long tenantId, RestClientException failure) {
    // The cause is not attached: a downstream response body must not reach logs or API errors.
    log.warn(
        "Direct invitation switch for tenant {} could not be read: {}",
        tenantId,
        failure.getClass().getSimpleName());
    return new ServiceUnavailableException("The direct invitation setting is not available");
  }

  /** Both the Träger (read fresh) and the served Beratungsstelle value must be true. */
  private Boolean ofAgency(Long tenantId, Long agencyId, InviteLinkChatType medium) {
    if (!Boolean.TRUE.equals(ofTenant(tenantId, medium))) {
      return Boolean.FALSE;
    }
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
