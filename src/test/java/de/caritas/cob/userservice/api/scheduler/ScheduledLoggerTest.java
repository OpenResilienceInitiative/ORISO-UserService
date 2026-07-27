package de.caritas.cob.userservice.api.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;

class ScheduledLoggerTest {

  @Test
  void shouldAcceptTheRuntimeMeterRegistry() {
    assertThatCode(() -> ScheduledLogger.class.getDeclaredConstructor(MeterRegistry.class))
        .doesNotThrowAnyException();
  }

  @Test
  void shouldMeasureSuccessfulExecutionCountAndDuration() throws Throwable {
    var registry = new SimpleMeterRegistry();
    var logger = new ScheduledLogger(registry);
    var joinPoint = joinPoint("EnquiryNotificationScheduler.performEnquiryNotifications()");

    logger.logMethod(joinPoint);

    assertThat(
            registry
                .get("userservice.scheduler.executions")
                .tags(
                    "task",
                    "EnquiryNotificationScheduler.performEnquiryNotifications()",
                    "outcome",
                    "success")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("userservice.scheduler.duration")
                .tags(
                    "task",
                    "EnquiryNotificationScheduler.performEnquiryNotifications()",
                    "outcome",
                    "success")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  void shouldPreserveFailuresAndMeasureTheirCountAndDuration() throws Throwable {
    var registry = new SimpleMeterRegistry();
    var logger = new ScheduledLogger(registry);
    var joinPoint = joinPoint("DeleteUserAccountScheduler.performDeletion()");
    var failure = new IllegalStateException("dependency unavailable");
    when(joinPoint.proceed()).thenThrow(failure);

    assertThatThrownBy(() -> logger.logMethod(joinPoint)).isSameAs(failure);

    assertThat(
            registry
                .get("userservice.scheduler.executions")
                .tags("task", "DeleteUserAccountScheduler.performDeletion()", "outcome", "failure")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("userservice.scheduler.duration")
                .tags("task", "DeleteUserAccountScheduler.performDeletion()", "outcome", "failure")
                .timer()
                .count())
        .isEqualTo(1);
  }

  private ProceedingJoinPoint joinPoint(String task) {
    var joinPoint = mock(ProceedingJoinPoint.class);
    var signature = mock(Signature.class);
    when(joinPoint.getSignature()).thenReturn(signature);
    when(signature.toShortString()).thenReturn(task);
    return joinPoint;
  }
}
