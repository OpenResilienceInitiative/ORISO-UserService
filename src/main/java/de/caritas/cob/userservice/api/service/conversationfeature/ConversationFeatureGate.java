package de.caritas.cob.userservice.api.service.conversationfeature;

import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.service.agency.EffectiveAgencySettingsLookup;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Refuses an action when the admin switched its feature off (ORISO-UserService#1564). Until now
 * only the counselling app obeyed these switches; a modified client or a direct API call could
 * still use the feature.
 *
 * <p>The effective switch is the Träger's value AND the Beratungsstelle's value, with the same
 * reading as {@code EffectiveDirectInvitePolicy}: the Träger value is read fresh from the
 * TenantService, because the AgencyService caches tenant data for ten minutes and serves the
 * Beratungsstelle's own value when its TenantService read fails. A master switch and the switch for
 * the kind of conversation must both allow.
 *
 * <p>Two different "not known" cases: a switch nobody has set is <b>on</b>, as in the counselling
 * app and in the call policy (the settings of existing Träger carry none of the newer switches). A
 * switch that cannot be <b>read</b> (settings not served, Beratungsstelle unknown) is refused with
 * 403. A provider that answers with a 4xx (other than 408 and 429) has understood the question and
 * said no, which is a refusal too; timeouts, 5xx, 408, 429 and unreadable answers are an outage and
 * answer 503, as {@code EffectiveDirectInvitePolicy} does. Never "allowed on doubt". Nothing is
 * cached here, so an admin's change applies to the next request.
 *
 * <p>The Träger is asked first: when it says no, the Beratungsstelle is not asked at all, so a
 * switched-off Träger answers 403 even while the AgencyService is down.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationFeatureGate {

  /** The master switch of a feature and the switch per kind of conversation, if there is one. */
  private record Flags<S>(
      Function<S, Boolean> master, Map<ConversationKind, Function<S, Boolean>> perKind) {

    boolean allows(S settings, ConversationKind kind) {
      var kindFlag = perKind.get(kind);
      return !Boolean.FALSE.equals(master.apply(settings))
          && (kindFlag == null || !Boolean.FALSE.equals(kindFlag.apply(settings)));
    }
  }

  private static final Map<
          ConversationFeature,
          Flags<de.caritas.cob.userservice.tenantservice.generated.web.model.Settings>>
      TRAEGER =
          Map.of(
              ConversationFeature.SUPERVISION,
              new Flags<>(
                  de.caritas.cob.userservice.tenantservice.generated.web.model.Settings
                      ::getFeatureSupervisionEnabled,
                  Map.of(
                      ConversationKind.ANONYMOUS,
                      de.caritas.cob.userservice.tenantservice.generated.web.model.Settings
                          ::getFeatureSupervisionAnonymousChatsEnabled,
                      ConversationKind.ONE_ON_ONE,
                      de.caritas.cob.userservice.tenantservice.generated.web.model.Settings
                          ::getFeatureSupervisionOneOnOneChatsEnabled)),
              ConversationFeature.MEDIA_UPLOAD,
              new Flags<>(
                  de.caritas.cob.userservice.tenantservice.generated.web.model.Settings
                      ::getFeatureMediaUploadEnabled,
                  Map.of(
                      ConversationKind.ANONYMOUS,
                      de.caritas.cob.userservice.tenantservice.generated.web.model.Settings
                          ::getFeatureMediaUploadAnonymousChatsEnabled,
                      ConversationKind.ONE_ON_ONE,
                      de.caritas.cob.userservice.tenantservice.generated.web.model.Settings
                          ::getFeatureMediaUploadOneOnOneChatsEnabled,
                      ConversationKind.GROUP,
                      de.caritas.cob.userservice.tenantservice.generated.web.model.Settings
                          ::getFeatureMediaUploadGroupChatsEnabled)),
              ConversationFeature.VOICE_MESSAGES,
              new Flags<>(
                  de.caritas.cob.userservice.tenantservice.generated.web.model.Settings
                      ::getFeatureVoiceMessagesEnabled,
                  Map.of(
                      ConversationKind.ANONYMOUS,
                      de.caritas.cob.userservice.tenantservice.generated.web.model.Settings
                          ::getFeatureVoiceMessagesAnonymousChatsEnabled,
                      ConversationKind.ONE_ON_ONE,
                      de.caritas.cob.userservice.tenantservice.generated.web.model.Settings
                          ::getFeatureVoiceMessagesOneOnOneChatsEnabled,
                      ConversationKind.GROUP,
                      de.caritas.cob.userservice.tenantservice.generated.web.model.Settings
                          ::getFeatureVoiceMessagesGroupChatsEnabled)));

  private static final Map<ConversationFeature, Flags<Settings>> AGENCY =
      Map.of(
          ConversationFeature.SUPERVISION,
          new Flags<>(
              Settings::getFeatureSupervisionEnabled,
              Map.of(
                  ConversationKind.ANONYMOUS, Settings::getFeatureSupervisionAnonymousChatsEnabled,
                  ConversationKind.ONE_ON_ONE,
                      Settings::getFeatureSupervisionOneOnOneChatsEnabled)),
          ConversationFeature.MEDIA_UPLOAD,
          new Flags<>(
              Settings::getFeatureMediaUploadEnabled,
              Map.of(
                  ConversationKind.ANONYMOUS, Settings::getFeatureMediaUploadAnonymousChatsEnabled,
                  ConversationKind.ONE_ON_ONE, Settings::getFeatureMediaUploadOneOnOneChatsEnabled,
                  ConversationKind.GROUP, Settings::getFeatureMediaUploadGroupChatsEnabled)),
          ConversationFeature.VOICE_MESSAGES,
          new Flags<>(
              Settings::getFeatureVoiceMessagesEnabled,
              Map.of(
                  ConversationKind.ANONYMOUS,
                      Settings::getFeatureVoiceMessagesAnonymousChatsEnabled,
                  ConversationKind.ONE_ON_ONE,
                      Settings::getFeatureVoiceMessagesOneOnOneChatsEnabled,
                  ConversationKind.GROUP, Settings::getFeatureVoiceMessagesGroupChatsEnabled)));

  private final @NonNull TenantService tenantService;
  private final @NonNull EffectiveAgencySettingsLookup effectiveAgencySettingsLookup;
  private final @NonNull ChatAgencyRepository chatAgencyRepository;

  /** Refuses unless the feature is on for this session's Träger and Beratungsstelle. */
  public void require(ConversationFeature feature, Session session) {
    require(List.of(feature), session);
  }

  /** Refuses unless every one of the features is on for this session. */
  public void require(List<ConversationFeature> features, Session session) {
    var agencyIds =
        session.getAgencyId() == null ? List.<Long>of() : List.of(session.getAgencyId());
    requireAll(features, ConversationKind.of(session), session.getTenantId(), agencyIds);
  }

  /**
   * Refuses unless the feature is on for this group chat's Träger and each of its Beratungsstellen.
   */
  public void require(ConversationFeature feature, Chat chat) {
    require(List.of(feature), chat);
  }

  /**
   * Refuses unless every one of the features is on for the group chat's Träger and for every
   * Beratungsstelle the chat belongs to: one that says no is enough.
   */
  public void require(List<ConversationFeature> features, Chat chat) {
    Long tenantId = chat.getChatOwner() == null ? null : chat.getChatOwner().getTenantId();
    var agencyIds = new LinkedHashSet<Long>();
    for (ChatAgency agency : chatAgencyRepository.findByChat_Id(chat.getId())) {
      agencyIds.add(agency.getAgencyId());
    }
    requireAll(features, ConversationKind.GROUP, tenantId, List.copyOf(agencyIds));
  }

  /**
   * @param tenantId the Träger of the conversation, null in a single-Träger installation
   * @param agencyId the Beratungsstelle, null when the conversation has none
   * @throws CustomValidationHttpStatusException 403 with reason FEATURE_DISABLED when switched off
   *     or not readable
   * @throws ServiceUnavailableException 503 when the TenantService or AgencyService is unreachable
   */
  public void require(
      ConversationFeature feature, ConversationKind kind, Long tenantId, Long agencyId) {
    requireAll(
        List.of(feature), kind, tenantId, agencyId == null ? List.<Long>of() : List.of(agencyId));
  }

  private void requireAll(
      List<ConversationFeature> features,
      ConversationKind kind,
      Long tenantId,
      List<Long> agencyIds) {
    var allowed = new AtomicBoolean(false);
    try {
      if (tenantId == null) {
        allowed.set(allows(features, kind, null, agencyIds));
      } else {
        // The caller may belong to another Träger than the conversation (members-only chats):
        // ask in the conversation's Träger.
        TenantContext.runIn(
            tenantId, () -> allowed.set(allows(features, kind, tenantId, agencyIds)));
      }
    } catch (ForbiddenException refused) {
      // The AgencyService refused to serve the Beratungsstelle: nothing to read, so no.
      log.warn(
          "Feature switches {} for tenant {} refused: the settings were not served",
          features,
          tenantId);
      throw featureDisabled();
    } catch (HttpClientErrorException refused) {
      if (isTransient(refused)) {
        throw unavailable(features, tenantId, refused);
      }
      // The provider understood the question and said no (for example 400): a refusal, not an
      // outage. Only the status is logged, never the body.
      log.warn(
          "Feature switches {} for tenant {} were refused by their provider with {}",
          features,
          tenantId,
          refused.getStatusCode().value());
      throw featureDisabled();
    } catch (RestClientException failure) {
      throw unavailable(features, tenantId, failure);
    }
    if (!allowed.get()) {
      throw featureDisabled();
    }
  }

  /**
   * Whether every switch that applies is on. The Träger is asked first and, when it says no, the
   * Beratungsstellen are not asked at all.
   */
  private boolean allows(
      List<ConversationFeature> features,
      ConversationKind kind,
      Long tenantId,
      List<Long> agencyIds) {
    var tenant =
        tenantId == null
            ? tenantService.getSingleTenancyTenantDataFresh()
            : tenantService.getRestrictedTenantDataFresh(tenantId);
    if (tenant == null
        || tenant.getSettings() == null
        || (tenantId != null && !tenantId.equals(tenant.getId()))) {
      log.warn("Feature switches {} refused: the Traeger settings are not served", features);
      return false;
    }
    if (!allOn(TRAEGER, tenant.getSettings(), features, kind)) {
      return false;
    }
    for (Long agencyId : agencyIds) {
      var agency = effectiveAgencySettingsLookup.findEffectiveSettings(agencyId, tenantId);
      if (agency.isEmpty()) {
        log.warn(
            "Feature switches {} refused: the Beratungsstelle settings are not served", features);
        return false;
      }
      if (!allOn(AGENCY, agency.get(), features, kind)) {
        return false;
      }
    }
    return true;
  }

  private static <S> boolean allOn(
      Map<ConversationFeature, Flags<S>> table,
      S settings,
      List<ConversationFeature> features,
      ConversationKind kind) {
    return features.stream().allMatch(feature -> table.get(feature).allows(settings, kind));
  }

  /** Of the 4xx only these two are an outage; a timeout or a 5xx never gets here. */
  private static boolean isTransient(HttpClientErrorException refused) {
    return refused.getStatusCode().value() == 408 || refused.getStatusCode().value() == 429;
  }

  private ServiceUnavailableException unavailable(
      List<ConversationFeature> features, Long tenantId, RestClientException failure) {
    // The cause is not attached: a downstream response body must not reach logs or API errors.
    log.warn(
        "Feature switches {} for tenant {} could not be read: {}",
        features,
        tenantId,
        failure.getClass().getSimpleName());
    return new ServiceUnavailableException("The feature setting is not available");
  }

  private static CustomValidationHttpStatusException featureDisabled() {
    return new CustomValidationHttpStatusException(
        HttpStatusExceptionReason.FEATURE_DISABLED, HttpStatus.FORBIDDEN);
  }
}
