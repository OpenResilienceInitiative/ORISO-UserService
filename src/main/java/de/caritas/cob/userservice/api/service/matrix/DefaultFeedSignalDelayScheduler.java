package de.caritas.cob.userservice.api.service.matrix;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * One daemon timer thread for the coalescing windows (ADR-020). It only waits and hands over — the
 * Matrix call itself runs on the bounded {@code matrixFeedSignalExecutor}.
 */
@Slf4j
@Component
public class DefaultFeedSignalDelayScheduler implements FeedSignalDelayScheduler {

  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            var thread = new Thread(runnable, "feed-signal-timer");
            // Daemon: a pending best-effort signal must never hold up JVM shutdown.
            thread.setDaemon(true);
            return thread;
          });

  @Override
  public void schedule(Runnable task, long delayMillis) {
    try {
      timer.schedule(task, Math.max(delayMillis, 0L), TimeUnit.MILLISECONDS);
    } catch (RuntimeException ex) {
      // Shutting down, or otherwise unable to queue the timer. Best effort: drop it.
      log.warn("Could not schedule feed-update signal: {}", ex.getMessage());
    }
  }

  @PreDestroy
  void shutdown() {
    timer.shutdownNow();
  }
}
