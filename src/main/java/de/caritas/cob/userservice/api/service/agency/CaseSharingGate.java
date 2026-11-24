package de.caritas.cob.userservice.api.service.agency;

import de.caritas.cob.userservice.agencyserivce.generated.web.model.AgencyCaseSharingPolicy;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.ConversationType;
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
    var mode = caseSharing == null ? null : caseSharing.getValue();
    if (mode != null && mode != AgencyCaseSharingPolicy.ValueEnum.CASE_HANDOVER) {
      throw featureDisabled();
    }
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
