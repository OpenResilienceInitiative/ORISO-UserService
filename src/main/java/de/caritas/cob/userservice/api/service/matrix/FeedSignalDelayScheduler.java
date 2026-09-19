package de.caritas.cob.userservice.api.service.matrix;

/**
 * Runs a task once, after a delay — the timer half of the trailing-edge coalescing window
 * (ADR-020).
 *
 * <p>Deliberately its own tiny abstraction rather than a Spring {@code TaskScheduler} bean: a lone
 * {@code TaskScheduler} (or {@code ScheduledExecutorService}) bean in the context is adopted by
 * {@code @EnableScheduling} as THE scheduler for every {@code @Scheduled} job in the service, which
 * would make feed signals and batch jobs starve each other. It also keeps the service
 * deterministically unit-testable without sleeping.
 */
public interface FeedSignalDelayScheduler {

  /**
   * @param task the work to run once the delay elapsed; must never throw
   * @param delayMillis delay in milliseconds; {@code <= 0} runs as soon as possible
   */
  void schedule(Runnable task, long delayMillis);
}
