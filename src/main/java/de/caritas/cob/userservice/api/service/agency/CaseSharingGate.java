package de.caritas.cob.userservice.api.service.agency;

import de.caritas.cob.userservice.agencyserivce.generated.web.model.AgencyCaseSharingPolicy;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.CaseSharingModel;
import de.caritas.cob.userservice.api.model.ConversationType;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * ORISO-UserService#1687 / ORISO-TenantService#374: case handover only where it is the chosen case
 * sharing model of the session's counselling centre, and only in Mailberatung. Team counselling and
 * "none" exclude it, so the two models are never on at the same time.
 *
 * <p>Error contract (docs/api-error-contract.md), as in {@code LiveChatFurtherCounsellingGate}: a
 * refused or unserved centre setting is a feature-disabled 403; a timeout, 408, 429 or 5xx is a
 * retryable 503.
 *
 * <p>An AgencyService that predates #374 serves a settings block without {@code caseSharing}; then
 * the Traeger master {@code caseHandoverEnabled}, checked by the caller, decides alone.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CaseSharingGate {

  private final @NonNull EffectiveAgencySettingsLookup effectiveAgencySettingsLookup;

  public void requireCaseHandover(Long agencyId, Long tenantId, ConversationType type) {
    if (type != null && type != ConversationType.AGENCY_COUNSELLING) {
      throw featureDisabled();
    }
    Settings settings = readSettings(agencyId, tenantId);
    if (settings == null) {
      log.warn("Case handover refused: agency {} settings were not served", agencyId);
      throw featureDisabled();
    }
    AgencyCaseSharingPolicy caseSharing = settings.getCaseSharing();
    // Only an absent block is the legacy fallback; a served policy must name case handover.
    if (caseSharing != null
        && caseSharing.getValue() != AgencyCaseSharingPolicy.ValueEnum.CASE_HANDOVER) {
      throw featureDisabled();
    }
  }

  /**
   * FE#2494 *4: the case keeps the model it was created under. A stamped case is decided by its
   * stamp alone, whatever the centre chose since; only a case created before the stamp existed
   * falls back to the centre's current model until the snapshot job stamps it.
   */
  public void requireCaseHandover(
      Long agencyId, Long tenantId, ConversationType type, CaseSharingModel stamped) {
    if (stamped == null) {
      requireCaseHandover(agencyId, tenantId, type);
      return;
    }
    if ((type != null && type != ConversationType.AGENCY_COUNSELLING)
        || stamped != CaseSharingModel.CASE_HANDOVER) {
      throw featureDisabled();
    }
  }

  /**
   * FE#2494 *4: the centre's current case sharing model, to stamp on a case. Empty when the setting
   * could not be read or was not served, so the caller retries instead of guessing. An
   * AgencyService that predates #374 serves no {@code caseSharing}: that legacy centre offered case
   * handover under the Traeger master switch, which the handover caller still checks.
   */
  public Optional<CaseSharingModel> currentModel(Long agencyId, Long tenantId) {
    Settings settings;
    try {
      settings = readSettings(agencyId, tenantId);
    } catch (RuntimeException unavailableOrRefused) {
      return Optional.empty();
    }
    if (settings == null) {
      return Optional.empty();
    }
    AgencyCaseSharingPolicy caseSharing = settings.getCaseSharing();
    if (caseSharing == null) {
      return Optional.of(CaseSharingModel.CASE_HANDOVER);
    }
    if (caseSharing.getValue() == null) {
      // a served policy without a value refuses case handover, as requireCaseHandover does
      return Optional.of(CaseSharingModel.NONE);
    }
    return Optional.of(CaseSharingModel.valueOf(caseSharing.getValue().name()));
  }

  /**
   * FE#2494 *4: the model to stamp on a case being created. A team flag means Teamberatung without
   * a further read. Otherwise the centre's current model; a non-team case in a centre that reads as
   * Teamberatung in this instant is stamped "none", never case handover: a handover right is never
   * inferred. Null when the setting cannot be read; the snapshot job stamps such a case later.
   */
  public CaseSharingModel modelForNewCase(Long agencyId, Long tenantId, boolean teamSession) {
    if (teamSession) {
      return CaseSharingModel.TEAM_COUNSELLING;
    }
    if (agencyId == null) {
      return null;
    }
    return currentModel(agencyId, tenantId)
        .map(model -> model == CaseSharingModel.TEAM_COUNSELLING ? CaseSharingModel.NONE : model)
        .orElse(null);
  }

  private Settings readSettings(Long agencyId, Long tenantId) {
    try {
      return effectiveAgencySettingsLookup.findEffectiveSettings(agencyId, tenantId).orElse(null);
    } catch (ForbiddenException refused) {
      log.warn("Case handover refused: agency {} settings were not served", agencyId);
      throw featureDisabled();
    } catch (HttpClientErrorException refused) {
      int status = refused.getStatusCode().value();
      if (status == 408 || status == 429) {
        throw unavailable(agencyId, refused);
      }
      log.warn("Case handover refused: agency {} settings answered {}", agencyId, status);
      throw featureDisabled();
    } catch (RestClientException failure) {
      throw unavailable(agencyId, failure);
    }
  }

  private static ServiceUnavailableException unavailable(Long agencyId, RestClientException cause) {
    // The cause is not attached: a downstream body must not reach logs or API errors.
    log.warn(
        "Case handover: agency {} settings could not be read: {}",
        agencyId,
        cause.getClass().getSimpleName());
    return new ServiceUnavailableException("The case sharing setting is not available");
  }

  private static CustomValidationHttpStatusException featureDisabled() {
    return new CustomValidationHttpStatusException(
        HttpStatusExceptionReason.FEATURE_DISABLED, HttpStatus.FORBIDDEN);
  }
}
