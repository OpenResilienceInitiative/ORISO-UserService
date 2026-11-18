package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.AgencyMembershipBackfillExecutorConfig;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService.ClaimLease;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@ExtendWith(MockitoExtension.class)
class AgencyEnquiryRevocationSchedulerTest {
  @Mock private AgencyEnquiryRevocations revocations;
  @Mock private ScheduledTaskClaimService claims;

  @Test
  void tickOnlySubmitsAndTheWorkerOwnsTheTechnicalTenantLease() {
    var submitted = new ArrayList<Runnable>();
    var scheduler = new AgencyEnquiryRevocationScheduler(revocations, claims, submitted::add);
    var lease =
        new ClaimLease("agency-enquiry-access-revocation", LocalDateTime.now().plusMinutes(2));
    when(claims.tryClaimLease(lease.taskName(), Duration.ofMinutes(2)))
        .thenReturn(Optional.of(lease));
    doAnswer(
            ignored -> {
              assertThat(TenantContext.getCurrentTenant())
                  .isEqualTo(TenantContext.TECHNICAL_TENANT_ID);
              return null;
            })
        .when(revocations)
        .retryDue();

    scheduler.retryPending();

    verifyNoInteractions(revocations, claims);
    assertThat(submitted).hasSize(1);
    submitted.getFirst().run();

    var order = inOrder(claims, revocations);
    order.verify(claims).tryClaimLease(lease.taskName(), Duration.ofMinutes(2));
    order.verify(revocations).retryDue();
    order.verify(claims).release(lease);
  }

  @Test
  void schedulerStillRunsWhenLateJoinerBackfillIsDisabled() {
    new ApplicationContextRunner()
        .withPropertyValues("matrix.membership.lateJoiner.enabled=false")
        .withUserConfiguration(AgencyEnquiryRevocationScheduler.class)
        .withBean(AgencyEnquiryRevocations.class, () -> revocations)
        .withBean(ScheduledTaskClaimService.class, () -> claims)
        .withBean(
            AgencyMembershipBackfillExecutorConfig.REVOCATION_EXECUTOR_BEAN,
            Executor.class,
            () -> Runnable::run)
        .run(context -> assertThat(context).hasSingleBean(AgencyEnquiryRevocationScheduler.class));
  }

  @Test
  void workerReleasesItsLeaseWhenTheRetryBatchThrows() {
    var scheduler = new AgencyEnquiryRevocationScheduler(revocations, claims, Runnable::run);
    var lease =
        new ClaimLease("agency-enquiry-access-revocation", LocalDateTime.now().plusMinutes(2));
    var failure = new IllegalStateException("Retry storage is temporarily unavailable");
    when(claims.tryClaimLease(lease.taskName(), Duration.ofMinutes(2)))
        .thenReturn(Optional.of(lease));
    doThrow(failure).when(revocations).retryDue();

    assertThatThrownBy(scheduler::retryPending).isSameAs(failure);
    verify(claims).release(lease);
  }

  @Test
  void aBusyWorkerDoesNotRunTheBatchOnTheSchedulerThread() {
    var scheduler =
        new AgencyEnquiryRevocationScheduler(
            revocations,
            claims,
            ignored -> {
              throw new RejectedExecutionException();
            });

    scheduler.retryPending();

    verifyNoInteractions(revocations, claims);
  }
}
