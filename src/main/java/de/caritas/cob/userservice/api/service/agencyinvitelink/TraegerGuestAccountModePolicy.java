package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.GuestAccountMode;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * #1662: the Träger cap. The modes a counsellor may offer are the platform's ({@link
 * PlatformGuestAccountModePolicy}) AND those the Träger allows for the medium ({@code
 * featureGuestAccount<Mode><Medium>Enabled}, TenantService; the platform's enforced choices are
 * already resolved into the Träger's effective value there). Read fresh, like the "Direkteinladung
 * erlauben" switch ({@link EffectiveDirectInvitePolicy}), so an admin's change applies to the next
 * request, also for links created earlier.
 *
 * <p>A field the TenantService does not serve (an older release) restricts nothing: the setting is
 * an opt-out. A Träger that cannot be read, or that switched off every mode, offers permanent
 * accounts only, which never creates an account ORISO deletes. An unreachable TenantService is an
 * outage (503).
 */
@Service
@Primary
@RequiredArgsConstructor
@Slf4j
public class TraegerGuestAccountModePolicy implements GuestAccountModePolicy {

  private final @NonNull PlatformGuestAccountModePolicy platform;
  private final @NonNull TenantService tenantService;

  @Override
  public Set<GuestAccountMode> allowedModes(
      Long tenantId, Long agencyId, InviteLinkChatType medium) {
    var allowed = EnumSet.copyOf(platform.allowedModes(tenantId, agencyId, medium));
    if (tenantId == null || medium == null || medium == InviteLinkChatType.LIVE_CHAT) {
      return allowed;
    }
    var settings = traegerSettings(tenantId);
    if (settings == null) {
      return EnumSet.of(GuestAccountMode.PERMANENT);
    }
    allowed.removeIf(mode -> Boolean.FALSE.equals(switchOf(settings, medium, mode)));
    return allowed.isEmpty() ? EnumSet.of(GuestAccountMode.PERMANENT) : allowed;
  }

  private Settings traegerSettings(Long tenantId) {
    // A guest opening a link has no tenant context of their own yet: ask in the link's Träger.
    var answer = new AtomicReference<Settings>();
    try {
      TenantContext.runIn(
          tenantId,
          () -> {
            var tenant = tenantService.getRestrictedTenantDataFresh(tenantId);
            if (tenant != null && tenantId.equals(tenant.getId())) {
              answer.set(tenant.getSettings());
            }
          });
    } catch (HttpClientErrorException refused) {
      int status = refused.getStatusCode().value();
      if (status == 408 || status == 429) {
        throw unavailable(tenantId, refused);
      }
      log.warn("Guest account modes of tenant {} were refused with {}", tenantId, status);
      return null;
    } catch (RestClientException failure) {
      throw unavailable(tenantId, failure);
    }
    return answer.get();
  }

  private ServiceUnavailableException unavailable(Long tenantId, RestClientException failure) {
    // The cause is not attached: a downstream response body must not reach logs or API errors.
    log.warn(
        "Guest account modes of tenant {} could not be read: {}",
        tenantId,
        failure.getClass().getSimpleName());
    return new ServiceUnavailableException("The guest account mode setting is not available");
  }

  private static Boolean switchOf(
      Settings settings, InviteLinkChatType medium, GuestAccountMode mode) {
    return switch (medium) {
      case CHAT ->
          switch (mode) {
            case TEMPORARY -> settings.getFeatureGuestAccountTemporaryChatEnabled();
            case GUEST_CHOOSES -> settings.getFeatureGuestAccountGuestChoosesChatEnabled();
            case PERMANENT -> settings.getFeatureGuestAccountPermanentChatEnabled();
          };
      case AUDIO ->
          switch (mode) {
            case TEMPORARY -> settings.getFeatureGuestAccountTemporaryAudioEnabled();
            case GUEST_CHOOSES -> settings.getFeatureGuestAccountGuestChoosesAudioEnabled();
            case PERMANENT -> settings.getFeatureGuestAccountPermanentAudioEnabled();
          };
      case VIDEO ->
          switch (mode) {
            case TEMPORARY -> settings.getFeatureGuestAccountTemporaryVideoEnabled();
            case GUEST_CHOOSES -> settings.getFeatureGuestAccountGuestChoosesVideoEnabled();
            case PERMANENT -> settings.getFeatureGuestAccountPermanentVideoEnabled();
          };
      case LIVE_CHAT -> null;
    };
  }
}
