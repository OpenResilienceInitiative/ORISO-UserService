package de.caritas.cob.userservice.api.service.session;

import de.caritas.cob.userservice.api.model.CaseSharingModel;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.CaseSharingGate;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * FE#2494 *4: each case keeps the case sharing model it was created under. Cases created before the
 * model was stamped get it once from their centre's model at the time of this snapshot. A case that
 * was not created as a team case is never stamped as Teamberatung: a running case stays as it is
 * when the centre switches. A handover right is never inferred. Only the stamp is written; no
 * Matrix power level and no access changes. Once every case is stamped, a run finds nothing and
 * does nothing.
 */
@Component
@Profile("!testing")
@RequiredArgsConstructor
@Slf4j
public class CaseSharingModelSnapshotScheduler {

  public static final String TASK = "case-sharing-model-snapshot";
  static final Pageable BATCH = PageRequest.of(0, 50);

  private final SessionRepository sessions;
  private final CaseSharingGate caseSharing;
  private final ScheduledTaskClaimService claims;

  private long cursor;

  @Scheduled(fixedDelayString = "${case-sharing.model-snapshot.delay-ms:60000}")
  public void snapshot() {
    var lease = claims.tryClaimLease(TASK, Duration.ofMinutes(2));
    if (lease.isEmpty()) return;
    try {
      TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
      var ids = sessions.findUnstampedCaseIds(cursor, BATCH);
      if (ids.isEmpty()) {
        cursor = 0;
        return;
      }
      Map<String, Optional<CaseSharingModel>> centres = new HashMap<>();
      for (Long id : ids) {
        try {
          if (!claims.runIfHeld(lease.get(), () -> stamp(id, centres))) break;
        } catch (RuntimeException failure) {
          log.warn("Case sharing model snapshot remains pending for session {}", id);
        }
        cursor = id;
      }
    } finally {
      TenantContext.clear();
      claims.release(lease.get());
    }
  }

  private void stamp(Long id, Map<String, Optional<CaseSharingModel>> centres) {
    sessions
        .findById(id)
        .filter(session -> session.getCaseSharingModel() == null)
        .ifPresent(
            session ->
                modelAtSnapshot(session, centres)
                    .ifPresent(model -> sessions.stampCaseSharingModel(id, model)));
  }

  /**
   * A team flag records the model the case was created under. Any other case was created as case
   * handover or "none": it takes the centre's current model. A centre now on Teamberatung does not
   * turn it into a team case, and since its earlier model is unknown it is stamped "none": a
   * handover right is never inferred.
   */
  Optional<CaseSharingModel> modelAtSnapshot(
      Session session, Map<String, Optional<CaseSharingModel>> centres) {
    if (session.isTeamCounsellingCase()) {
      return Optional.of(CaseSharingModel.TEAM_COUNSELLING);
    }
    return centres
        .computeIfAbsent(
            session.getAgencyId() + "/" + session.getTenantId(),
            key -> caseSharing.currentModel(session.getAgencyId(), session.getTenantId()))
        .map(model -> model == CaseSharingModel.TEAM_COUNSELLING ? CaseSharingModel.NONE : model);
  }
}
