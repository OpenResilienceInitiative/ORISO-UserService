package de.caritas.cob.userservice.api.service.conversationfeature;

import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.service.agency.EffectiveAgencySettingsLookup;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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
 * 403, and an unreachable TenantService or AgencyService answers 503: never "allowed on doubt".
 * Nothing is cached here, so an admin's change applies to the next request.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationFeatureGate {

  /** The master switch of a feature and the switch per kind of conversation, if there is one. */
  private record Flags<S>(
      Function<S, Boolean> master, Map<ConversationKind, Function<S, Boolean>> perKind) {

    List<Boolean> relevantFor(S settings, ConversationKind kind) {
      var kindFlag = perKind.get(kind);
      var values = new java.util.ArrayList<Boolean>();
      values.add(master.apply(settings));
      if (kindFlag != null) {
        values.add(kindFlag.apply(settings));
      }
      return values;
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
                          ::getFeatureMediaUploadGroupChatsEnabled,
                      ConversationKind.SUPERVISION,
                      de.caritas.cob.userservice.tenantservice.generated.web.model.Settings
                          ::getFeatureMediaUploadSupervisionChatsEnabled)));

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
                  ConversationKind.GROUP, Settings::getFeatureMediaUploadGroupChatsEnabled,
                  ConversationKind.SUPERVISION,
                      Settings::getFeatureMediaUploadSupervisionChatsEnabled)));

  private final @NonNull TenantService tenantService;
  private final @NonNull EffectiveAgencySettingsLookup effectiveAgencySettingsLookup;
  private final @NonNull ChatAgencyRepository chatAgencyRepository;

  /** Refuses unless the feature is on for this session's Träger and Beratungsstelle. */
  public void require(ConversationFeature feature, Session session) {
    require(feature, ConversationKind.of(session), session.getTenantId(), session.getAgencyId());
  }

  /**
   * Refuses unless the feature is on for this group chat's Träger and, when the chat belongs to
   * exactly one Beratungsstelle (as the app reads it), for that Beratungsstelle.
   */
  public void require(ConversationFeature feature, Chat chat) {
    Long tenantId = chat.getChatOwner() == null ? null : chat.getChatOwner().getTenantId();
    List<ChatAgency> agencies = chatAgencyRepository.findByChat_Id(chat.getId());
    Long agencyId = agencies.size() == 1 ? agencies.getFirst().getAgencyId() : null;
    require(feature, ConversationKind.GROUP, tenantId, agencyId);
  }

  /**
   * @param tenantId the Träger of the conversation, null in a single-Träger installation
   * @param agencyId the Beratungsstelle, null when the conversation has none or several
   * @throws CustomValidationHttpStatusException 403 with reason FEATURE_DISABLED when switched off
   *     or not readable
   * @throws ServiceUnavailableException 503 when the TenantService or AgencyService is unreachable
   */
  public void require(
      ConversationFeature feature, ConversationKind kind, Long tenantId, Long agencyId) {
    var verdicts = new AtomicReference<List<Boolean>>();
    try {
      if (tenantId == null) {
        verdicts.set(evaluate(feature, kind, null, agencyId));
      } else {
        // The caller may belong to another Träger than the conversation (members-only chats):
        // ask in the conversation's Träger.
        TenantContext.runIn(
            tenantId, () -> verdicts.set(evaluate(feature, kind, tenantId, agencyId)));
      }
    } catch (RestClientException unavailable) {
      log.warn(
          "Feature switch {} for tenant {} could not be read: {}",
          feature,
          tenantId,
          unavailable.getClass().getSimpleName());
      throw new ServiceUnavailableException("The feature setting is not available");
    }
    var answer = verdicts.get();
    if (answer == null || answer.stream().anyMatch(Boolean.FALSE::equals)) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.FEATURE_DISABLED, HttpStatus.FORBIDDEN);
    }
  }

  /** All switches that apply; {@code null} when they cannot be read. */
  private List<Boolean> evaluate(
      ConversationFeature feature, ConversationKind kind, Long tenantId, Long agencyId) {
    var tenant =
        tenantId == null
            ? tenantService.getSingleTenancyTenantDataFresh()
            : tenantService.getRestrictedTenantDataFresh(tenantId);
    if (tenant == null
        || tenant.getSettings() == null
        || (tenantId != null && !tenantId.equals(tenant.getId()))) {
      log.warn("Feature switch {} refused: the Traeger settings are not served", feature);
      return null;
    }
    var values =
        new java.util.ArrayList<>(TRAEGER.get(feature).relevantFor(tenant.getSettings(), kind));
    if (agencyId != null) {
      var agency = effectiveAgencySettingsLookup.findEffectiveSettings(agencyId, tenantId);
      if (agency.isEmpty()) {
        log.warn("Feature switch {} refused: the Beratungsstelle settings are not served", feature);
        return null;
      }
      values.addAll(AGENCY.get(feature).relevantFor(agency.get(), kind));
    }
    return values;
  }
}
