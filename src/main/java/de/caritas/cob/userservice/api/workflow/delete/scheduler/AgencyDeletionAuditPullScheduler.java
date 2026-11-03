package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.workflow.delete.service.AgencyDeletionAuditPull;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pulls finished agency purges from AgencyService into the deletion log (OBP 2.1 #130). Runs on one
 * replica at a time through the scheduled-task lease.
 *
 * <p>{@code deletion.audit.agency-outbox.enabled} defaults to on exactly when {@code
 * TASK_IDENTITY_MODEL=FIVE_ACTORS}; it can switch the pull off, never on in legacy mode, because
 * AgencyService only lets the RUNTIME task actor read the outbox.
 */
@Component
@Profile("!testing")
@Slf4j
public class AgencyDeletionAuditPullScheduler {

  static final String TASK_NAME = "agency-deletion-audit-pull";
  static final String ENABLED = "deletion.audit.agency-outbox.enabled";
  private static final Duration CLAIM = Duration.ofMinutes(5);

  private final AgencyDeletionAuditPull pull;
  private final ScheduledTaskClaimService claims;
  private final IdentityClientConfig identity;
  private final Environment environment;

  public AgencyDeletionAuditPullScheduler(
      AgencyDeletionAuditPull pull,
      ScheduledTaskClaimService claims,
      IdentityClientConfig identity,
      Environment environment) {
    this.pull = pull;
    this.claims = claims;
    this.identity = identity;
    this.environment = environment;
  }

  @Scheduled(
      initialDelayString = "${deletion.audit.agency-outbox.initial-delay-ms:120000}",
      fixedDelayString = "${deletion.audit.agency-outbox.pull-delay-ms:600000}")
  public void pull() {
    if (!enabled()) {
      return;
    }
    var lease = claims.tryClaimLease(TASK_NAME, CLAIM);
    if (lease.isEmpty()) {
      return;
    }
    try {
      int acknowledged = pull.pullOnce();
      if (acknowledged > 0) {
        log.info("Recorded {} agency deletions from the AgencyService outbox", acknowledged);
      }
    } catch (RuntimeException failure) {
      log.warn("Agency deletion outbox pull failed; retrying next run ({})", failure.getMessage());
    } finally {
      try {
        claims.release(lease.get());
      } catch (RuntimeException failure) {
        log.warn(
            "Could not release agency deletion outbox claim ({})",
            failure.getClass().getSimpleName());
      }
    }
  }

  private boolean enabled() {
    var configured = environment.getProperty(ENABLED, Boolean.class);
    if (Boolean.FALSE.equals(configured)) {
      return false;
    }
    try {
      return identity.isFiveActorMode();
    } catch (RuntimeException unsupportedModel) {
      return false;
    }
  }
}
