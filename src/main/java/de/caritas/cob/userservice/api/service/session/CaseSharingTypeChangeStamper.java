package de.caritas.cob.userservice.api.service.session;

import de.caritas.cob.userservice.api.model.CaseSharingModel;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.CaseSharingGate;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * FE#2494 *4: a centre's type change affects new cases only. AgencyService calls the type change
 * before it flips the type, so the centre still answers with its pre-switch model here. Cases the
 * snapshot job has not stamped yet are stamped with that model first, so the job can never give
 * them the post-switch model.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CaseSharingTypeChangeStamper {

  private final @NonNull SessionRepository sessions;
  private final @NonNull CaseSharingGate caseSharing;

  /**
   * @param agencyId the centre whose type changes
   * @param tenantId the centre's tenant
   * @param toTeam whether the centre becomes a Teamberatung centre
   */
  public void stampBeforeTypeChange(Long agencyId, Long tenantId, boolean toTeam) {
    // A team flag records the model the case was created under, in either direction.
    int team =
        sessions.stampUnstampedCasesOfAgency(
            agencyId, true, CaseSharingModel.TEAM_COUNSELLING, RegistrationType.ANONYMOUS);
    // Other cases: a Teamberatung centre switching back created them as "none"; a default centre
    // switching to Teamberatung created them under its current case sharing setting.
    // A refused or unreadable settings read throws here and refuses the type change.
    var model =
        toTeam ? caseSharing.requirePreSwitchModel(agencyId, tenantId) : CaseSharingModel.NONE;
    int other =
        sessions.stampUnstampedCasesOfAgency(agencyId, false, model, RegistrationType.ANONYMOUS);
    if (team + other > 0) {
      log.info(
          "Agency {} type change: stamped {} team and {} other unstamped cases ({}) first",
          agencyId,
          team,
          other,
          model);
    }
  }
}
