package de.caritas.cob.userservice.api.service.session;

import de.caritas.cob.userservice.api.model.CaseSharingModel;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository.UnstampedCase;
import de.caritas.cob.userservice.api.service.agency.CaseSharingGate;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * Matrix power level and no access changes.
 *
 * <p>A centre whose setting is refused or no longer served (for example a deleted centre) is
 * stamped "none" by {@link CaseSharingGate#currentModel}. A centre that is unreachable is retried
 * in later runs; after {@value #MAX_UNREACHABLE_RUNS} such runs its cases are stamped "none" too,
 * and so is a row that keeps failing for another reason, so no row is retried forever. Once a full
 * pass finds nothing, the job rests for {@code case-sharing.model-snapshot.idle-ms} (default six
 * hours) before it looks again.
 */
@Component
@Profile("!testing")
@Slf4j
public class CaseSharingModelSnapshotScheduler {

  public static final String TASK = "case-sharing-model-snapshot";
  static final Pageable BATCH = PageRequest.of(0, 50);
  static final int MAX_UNREACHABLE_RUNS = 10;
  static final int MAX_ROW_FAILURES = 5;

  private final SessionRepository sessions;
  private final CaseSharingGate caseSharing;
  private final ScheduledTaskClaimService claims;
  private final Clock clock;
  private final Duration idle;

  private long cursor;
  private boolean foundInThisPass;
  private Instant restUntil = Instant.MIN;
  private final Map<String, Integer> unreachableRuns = new HashMap<>();
  private final Map<Long, Integer> rowFailures = new HashMap<>();
  private final java.util.Set<Long> givenUp = new java.util.HashSet<>();

  @Autowired
  public CaseSharingModelSnapshotScheduler(
      SessionRepository sessions,
      CaseSharingGate caseSharing,
      ScheduledTaskClaimService claims,
      @Value("${case-sharing.model-snapshot.idle-ms:21600000}") long idleMs) {
    this(sessions, caseSharing, claims, Clock.systemUTC(), Duration.ofMillis(idleMs));
  }

  CaseSharingModelSnapshotScheduler(
      SessionRepository sessions,
      CaseSharingGate caseSharing,
      ScheduledTaskClaimService claims,
      Clock clock,
      Duration idle) {
    this.sessions = sessions;
    this.caseSharing = caseSharing;
    this.claims = claims;
    this.clock = clock;
    this.idle = idle;
  }

  @Scheduled(fixedDelayString = "${case-sharing.model-snapshot.delay-ms:60000}")
  public void snapshot() {
    if (clock.instant().isBefore(restUntil)) return;
    var lease = claims.tryClaimLease(TASK, Duration.ofMinutes(2));
    if (lease.isEmpty()) return;
    try {
      TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
      var cases = sessions.findUnstampedCases(cursor, RegistrationType.ANONYMOUS, BATCH);
      if (cases.isEmpty()) {
        endPass();
        return;
      }
      Map<String, Optional<CaseSharingModel>> centres = new HashMap<>();
      for (UnstampedCase unstamped : cases) {
        if (!givenUp.contains(unstamped.getId())) {
          foundInThisPass = true;
          try {
            if (!claims.runIfHeld(lease.get(), () -> stamp(unstamped, centres))) break;
            rowFailures.remove(unstamped.getId());
          } catch (RuntimeException failure) {
            recordFailure(unstamped.getId(), failure);
          }
        }
        cursor = unstamped.getId();
      }
    } finally {
      TenantContext.clear();
      claims.release(lease.get());
    }
  }

  /**
   * A row that keeps failing is stamped "none" after {@value #MAX_ROW_FAILURES} tries; if even that
   * fails it is left alone until the next restart, so it cannot keep the job from resting.
   */
  private void recordFailure(Long id, RuntimeException failure) {
    int failures = rowFailures.merge(id, 1, Integer::sum);
    if (failures < MAX_ROW_FAILURES) {
      log.warn(
          "Case sharing model snapshot remains pending for session {} ({})",
          id,
          failure.getClass().getSimpleName());
      return;
    }
    rowFailures.remove(id);
    try {
      sessions.stampCaseSharingModel(id, CaseSharingModel.NONE);
      log.warn("Session {} kept failing the snapshot; stamped none", id);
    } catch (RuntimeException stampFailure) {
      givenUp.add(id);
      log.error(
          "Session {} cannot be stamped ({}); the snapshot skips it until restart",
          id,
          stampFailure.getClass().getSimpleName());
    }
  }

  /** A pass that found nothing to stamp rests; one that did starts over for the cases it left. */
  private void endPass() {
    if (!foundInThisPass) {
      restUntil = clock.instant().plus(idle);
    }
    cursor = 0;
    foundInThisPass = false;
  }

  private void stamp(UnstampedCase unstamped, Map<String, Optional<CaseSharingModel>> centres) {
    modelAtSnapshot(unstamped, centres)
        .ifPresent(model -> sessions.stampCaseSharingModel(unstamped.getId(), model));
  }

  /**
   * A team flag records the model the case was created under. Any other case was created as case
   * handover or "none": it takes the centre's current model. A centre now on Teamberatung does not
   * turn it into a team case, and since its earlier model is unknown it is stamped "none": a
   * handover right is never inferred.
   */
  Optional<CaseSharingModel> modelAtSnapshot(
      UnstampedCase unstamped, Map<String, Optional<CaseSharingModel>> centres) {
    if (unstamped.isTeamSession()) {
      return Optional.of(CaseSharingModel.TEAM_COUNSELLING);
    }
    return centres
        .computeIfAbsent(
            unstamped.getAgencyId() + "/" + unstamped.getTenantId(),
            key -> readCentre(key, unstamped))
        .map(model -> model == CaseSharingModel.TEAM_COUNSELLING ? CaseSharingModel.NONE : model);
  }

  /** Read once per centre and run; an unreachable centre counts one run towards "none". */
  private Optional<CaseSharingModel> readCentre(String key, UnstampedCase unstamped) {
    var model = caseSharing.currentModel(unstamped.getAgencyId(), unstamped.getTenantId());
    if (model.isPresent()) {
      unreachableRuns.remove(key);
      return model;
    }
    // The count stays at the limit until the centre answers, so every later batch of the same
    // centre is stamped as well instead of starting the budget again.
    if (unreachableRuns.merge(key, 1, Integer::sum) >= MAX_UNREACHABLE_RUNS) {
      log.warn("Centre {} stayed unreachable; its unstamped cases are stamped none", key);
      return Optional.of(CaseSharingModel.NONE);
    }
    return Optional.empty();
  }
}
