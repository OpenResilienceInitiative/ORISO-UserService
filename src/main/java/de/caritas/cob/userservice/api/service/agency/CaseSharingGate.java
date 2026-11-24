package de.caritas.cob.userservice.api.service.agency;

import de.caritas.cob.userservice.agencyserivce.generated.web.model.AgencyCaseSharingPolicy;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.ConversationType;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ORISO-UserService#1687 / ORISO-TenantService#374: case handover only where it is the chosen case
 * sharing model of the session's counselling centre, and only in Mailberatung. Team counselling and
 * "none" exclude it, so the two models are never on at the same time.
 *
 * <p>An AgencyService that predates #374 serves no {@code caseSharing}; then the Traeger master
 * {@code caseHandoverEnabled}, checked by the caller, decides alone.
 */
@Component
@RequiredArgsConstructor
public class CaseSharingGate {

  private final @NonNull EffectiveAgencySettingsLookup effectiveAgencySettingsLookup;

  public void requireCaseHandover(Long agencyId, Long tenantId, ConversationType type) {
    if (type != null && type != ConversationType.AGENCY_COUNSELLING) {
      throw new ForbiddenException("Case Handover exists only in Mailberatung");
    }
    var mode =
        effectiveAgencySettingsLookup
            .findEffectiveSettings(agencyId, tenantId)
            .map(settings -> settings.getCaseSharing())
            .map(AgencyCaseSharingPolicy::getValue)
            .orElse(null);
    if (mode != null && mode != AgencyCaseSharingPolicy.ValueEnum.CASE_HANDOVER) {
      throw new ForbiddenException("Case Handover is not the case sharing model of this agency");
    }
  }
}
