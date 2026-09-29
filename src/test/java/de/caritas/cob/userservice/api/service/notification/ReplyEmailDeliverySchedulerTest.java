package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReplyEmailDeliverySchedulerTest {
  @InjectMocks private ReplyEmailDeliveryScheduler scheduler;
  @Mock private ReplyEmailDeliveryWriter writer;
  @Mock private AdviceSeekerReplyEmailService service;
  @Mock private ScheduledTaskClaimService claims;

  @Test
  void oneUncertainDeliveryDoesNotStopTheNextPendingMail() {
    var lease =
        new ScheduledTaskClaimService.ClaimLease(
            "reply-email-delivery", LocalDateTime.now().plusMinutes(2));
    when(claims.tryClaimLease(eq("reply-email-delivery"), any(Duration.class)))
        .thenReturn(Optional.of(lease));
    when(writer.pendingIds()).thenReturn(List.of(1L, 2L));
    doThrow(new IllegalStateException("uncertain SMTP outcome")).when(service).deliverPending(1L);

    scheduler.deliverPending();

    verify(service).deliverPending(1L);
    verify(service).deliverPending(2L);
    verify(claims).release(lease);
    assertThat(TenantContext.getCurrentTenant()).isNull();
  }
}
