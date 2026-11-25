package de.caritas.cob.userservice.api.service.session;

import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps accepted team sessions and lowers colleagues to readers (FE#2494 *4, decision d). Covers
 * the rooms that existed before Teamberatung became read-only and repairs a failed after-commit
 * enforcement or a later raise. Each run handles one batch and continues after the last id.
 */
@Component
@Profile("!testing")
@RequiredArgsConstructor
@Slf4j
public class TeamSessionReadOnlyRepairScheduler {

  public static final String TASK = "team-session-read-only-repair";
  static final Pageable BATCH = PageRequest.of(0, 20);

  private final SessionRepository sessions;
  private final TeamSessionReadOnlyService readOnly;
  private final ScheduledTaskClaimService claims;

  private long cursor;

  @Scheduled(fixedDelayString = "${team.session.read-only-repair.delay-ms:60000}")
  public void repair() {
    var lease = claims.tryClaimLease(TASK, Duration.ofMinutes(2));
    if (lease.isEmpty()) return;
    try {
      TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
      var ids = sessions.findTeamReadOnlyCandidates(SessionStatus.IN_PROGRESS, cursor, BATCH);
      if (ids.isEmpty()) {
        cursor = 0;
        return;
      }
      for (Long id : ids) {
        try {
          if (!claims.runIfHeld(lease.get(), () -> readOnly.repairSafely(id))) break;
        } catch (RuntimeException failure) {
          log.warn("Team read-only repair remains pending for session {}", id);
        }
        cursor = id;
      }
    } finally {
      TenantContext.clear();
      claims.release(lease.get());
    }
  }
}
