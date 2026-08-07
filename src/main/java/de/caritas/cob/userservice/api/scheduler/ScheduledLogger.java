package de.caritas.cob.userservice.api.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class ScheduledLogger {

  private final MeterRegistry meterRegistry;

  @SneakyThrows
  @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
  public void logMethod(ProceedingJoinPoint joinPoint) {
    val schedulerName = joinPoint.getSignature().toShortString();
    val sample = Timer.start(meterRegistry);
    try {
      log.info("{} Scheduler started", schedulerName);
      joinPoint.proceed();
      recordExecution(sample, schedulerName, "success");
    } catch (Throwable throwable) {
      recordExecution(sample, schedulerName, "failure");
      throw throwable;
    } finally {
      log.info("{} Scheduler completed", schedulerName);
    }
  }

  private void recordExecution(Timer.Sample sample, String schedulerName, String outcome) {
    Counter.builder("userservice.scheduler.executions")
        .description("Completed scheduled task executions")
        .tags("task", schedulerName, "outcome", outcome)
        .register(meterRegistry)
        .increment();
    sample.stop(
        Timer.builder("userservice.scheduler.duration")
            .description("Scheduled task execution duration")
            .tags("task", schedulerName, "outcome", outcome)
            .register(meterRegistry));
  }
}
