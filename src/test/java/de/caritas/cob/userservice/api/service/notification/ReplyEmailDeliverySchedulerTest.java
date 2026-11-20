package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.RecipientKind;
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
  @Mock private FeedbackMessageEmailService feedback;
  @Mock private InternalChatEmailService internalChat;
  @Mock private ScheduledTaskClaimService claims;

  @Test
  void oneUncertainDeliveryDoesNotStopTheNextPendingMail() {
    var lease =
        new ScheduledTaskClaimService.ClaimLease(
            "reply-email-delivery", LocalDateTime.now().plusMinutes(2));
    when(claims.tryClaimLease(eq("reply-email-delivery"), any(Duration.class)))
        .thenReturn(Optional.of(lease));
    when(writer.pendingIds()).thenReturn(List.of(1L, 2L));
    when(writer.kindOf(1L)).thenReturn(Optional.of(RecipientKind.ASKER));
    when(writer.kindOf(2L)).thenReturn(Optional.of(RecipientKind.ASKER));
    doThrow(new IllegalStateException("uncertain SMTP outcome")).when(service).deliverPending(1L);

    scheduler.deliverPending();

    verify(service).deliverPending(1L);
    verify(service).deliverPending(2L);
    verify(claims).release(lease);
    assertThat(TenantContext.getCurrentTenant()).isNull();
  }

  @Test
  void internalGroupIntentsAndClaimsReachTheInternalChatMailService() {
    var lease =
        new ScheduledTaskClaimService.ClaimLease(
            "reply-email-delivery", LocalDateTime.now().plusMinutes(2));
    when(claims.tryClaimLease(eq("reply-email-delivery"), any(Duration.class)))
        .thenReturn(Optional.of(lease));
    when(writer.pendingIds()).thenReturn(List.of(1L, 2L));
    when(writer.kindOf(1L)).thenReturn(Optional.of(RecipientKind.INTERNAL_INTENT));
    when(writer.kindOf(2L)).thenReturn(Optional.of(RecipientKind.INTERNAL));

    scheduler.deliverPending();

    verify(internalChat).resolveIntent(1L);
    verify(internalChat).deliverPending(2L);
  }

  @Test
  void quarantinedMailsRaiseAnOperatorAlert() {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(ReplyEmailDeliveryScheduler.class);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      var lease =
          new ScheduledTaskClaimService.ClaimLease(
              "reply-email-delivery", LocalDateTime.now().plusMinutes(2));
      when(claims.tryClaimLease(eq("reply-email-delivery"), any(Duration.class)))
          .thenReturn(Optional.of(lease));
      when(writer.quarantinedCount()).thenReturn(3L);
      when(writer.pendingIds()).thenReturn(List.of());

      scheduler.deliverPending();

      assertThat(appender.list)
          .anySatisfy(
              event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
                assertThat(event.getFormattedMessage())
                    .contains("3 reply-email deliveries are QUARANTINED");
              });
    } finally {
      logger.detachAppender(appender);
    }
  }
}
