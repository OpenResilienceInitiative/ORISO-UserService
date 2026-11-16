package de.caritas.cob.userservice.api.service.furthercounselling;

import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.service.agency.EffectiveAgencySettingsLookup;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * ORISO-Frontend#2251: further counselling may be recommended in an anonymous live chat only when
 * its Beratungsstelle switched {@code featureLiveChatFurtherCounsellingEnabled} on. The switch is
 * agency-owned and served as stored (null = off), read fresh and uncached.
 *
 * <p>Fails closed, following {@code docs/api-error-contract.md}: off, unset, not served or refused
 * is {@code 403 X-Reason: FEATURE_DISABLED}; an AgencyService outage (timeout, 408/429, 5xx,
 * unreadable answer) is {@code 503}. Registered 1:1 conversations are not affected.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LiveChatFurtherCounsellingGate {

  private final @NonNull EffectiveAgencySettingsLookup effectiveAgencySettingsLookup;

  public void requireAllowed(Session session) {
    if (!isLiveChat(session)) {
      return;
    }
    Long agencyId = session.getAgencyId();
    if (agencyId == null) {
      log.warn("Live-chat recommendation refused: session {} has no agency", session.getId());
      throw featureDisabled();
    }
    boolean enabled;
    try {
      enabled =
          effectiveAgencySettingsLookup
              .findEffectiveSettings(agencyId, session.getTenantId())
              .map(
                  settings ->
                      Boolean.TRUE.equals(settings.getFeatureLiveChatFurtherCounsellingEnabled()))
              .orElse(false);
    } catch (ForbiddenException refused) {
      log.warn("Live-chat recommendation refused: agency {} settings were not served", agencyId);
      throw featureDisabled();
    } catch (HttpClientErrorException refused) {
      int status = refused.getStatusCode().value();
      if (status == 408 || status == 429) {
        throw unavailable(agencyId, refused);
      }
      log.warn(
          "Live-chat recommendation refused: agency {} settings answered {}", agencyId, status);
      throw featureDisabled();
    } catch (RestClientException failure) {
      throw unavailable(agencyId, failure);
    }
    if (!enabled) {
      throw featureDisabled();
    }
  }

  static boolean isLiveChat(Session session) {
    return session.getConversationType() == ConversationType.LIVE_CHAT
        || session.getRegistrationType() == RegistrationType.ANONYMOUS;
  }

  private static ServiceUnavailableException unavailable(Long agencyId, RestClientException cause) {
    // The cause is not attached: a downstream body must not reach logs or API errors.
    log.warn(
        "Live-chat recommendation: agency {} settings could not be read: {}",
        agencyId,
        cause.getClass().getSimpleName());
    return new ServiceUnavailableException("The feature setting is not available");
  }

  private static CustomValidationHttpStatusException featureDisabled() {
    return new CustomValidationHttpStatusException(
        HttpStatusExceptionReason.FEATURE_DISABLED, HttpStatus.FORBIDDEN);
  }
}
