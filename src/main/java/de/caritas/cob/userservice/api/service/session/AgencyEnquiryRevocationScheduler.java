package de.caritas.cob.userservice.api.service.session;

import de.caritas.cob.userservice.api.config.AgencyMembershipBackfillExecutorConfig;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!testing")
@ConditionalOnProperty(name = "matrix.membership.lateJoiner.enabled", matchIfMissing = true)
class AgencyEnquiryRevocationScheduler {

  private static final String TASK_NAME = "agency-enquiry-access-revocation";
  private final AgencyEnquiryRevocations revocations;
  private final ScheduledTaskClaimService claims;
  private final Executor executor;

  AgencyEnquiryRevocationScheduler(
      AgencyEnquiryRevocations revocations,
      ScheduledTaskClaimService claims,
      @Qualifier(AgencyMembershipBackfillExecutorConfig.REVOCATION_EXECUTOR_BEAN)
          Executor executor) {
    this.revocations = revocations;
    this.claims = claims;
    this.executor = executor;
  }

  @Scheduled(fixedDelayString = "${matrix.membership.revocation.retry-delay-ms:60000}")
  public void retryPending() {
    try {
      executor.execute(
          () -> TenantContext.runIn(TenantContext.TECHNICAL_TENANT_ID, this::runClaimedBatch));
    } catch (RejectedExecutionException busy) {
      // The current sweep owns the only worker. The next tick will pick up persisted work.
    }
  }

  private void runClaimedBatch() {
    claims
        .tryClaimLease(TASK_NAME, Duration.ofMinutes(2))
        .ifPresent(
            lease -> {
              try {
                revocations.retryDue();
              } finally {
                claims.release(lease);
              }
            });
  }
}
