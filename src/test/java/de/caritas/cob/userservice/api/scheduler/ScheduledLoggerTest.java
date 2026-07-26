package de.caritas.cob.userservice.api.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;

class ScheduledLoggerTest {

  @Test
  void scheduledExecutionPublishesSuccessCountAndDuration() throws Throwable {
    var meterRegistry = new SimpleMeterRegistry();
    var scheduledLogger = new ScheduledLogger(meterRegistry);
    var joinPoint = mock(ProceedingJoinPoint.class);
    var signature = mock(Signature.class);
    when(joinPoint.getSignature()).thenReturn(signature);
    when(signature.toShortString()).thenReturn("CleanupScheduler.run()");

    scheduledLogger.logMethod(joinPoint);

    assertThat(
            meterRegistry
                .get("userservice.scheduler.executions")
                .tag("task", "CleanupScheduler.run()")
                .tag("outcome", "success")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get("userservice.scheduler.duration")
                .tag("task", "CleanupScheduler.run()")
                .tag("outcome", "success")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  void failedScheduledExecutionPreservesFailureAndPublishesFailureSignals() throws Throwable {
    var meterRegistry = new SimpleMeterRegistry();
    var scheduledLogger = new ScheduledLogger(meterRegistry);
    var joinPoint = mock(ProceedingJoinPoint.class);
    var signature = mock(Signature.class);
    var failure = new IllegalStateException("dependency unavailable");
    when(joinPoint.getSignature()).thenReturn(signature);
    when(signature.toShortString()).thenReturn("NotificationScheduler.run()");
    when(joinPoint.proceed()).thenThrow(failure);

    assertThatThrownBy(() -> scheduledLogger.logMethod(joinPoint)).isSameAs(failure);

    assertThat(
            meterRegistry
                .get("userservice.scheduler.executions")
                .tag("task", "NotificationScheduler.run()")
                .tag("outcome", "failure")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get("userservice.scheduler.duration")
                .tag("task", "NotificationScheduler.run()")
                .tag("outcome", "failure")
                .timer()
                .count())
        .isEqualTo(1);
  }
}
