package de.caritas.cob.userservice.api.config;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated, small, bounded pool for the P2 feed-update signal (ADR-020).
 *
 * <p>Separate from the application-wide {@code taskExecutor} on purpose: signalling is best-effort
 * telemetry-grade work and must never compete with, or be starved by, the general async pool.
 *
 * <p>The rejection policy is {@link ThreadPoolExecutor.AbortPolicy}, never {@code CallerRunsPolicy}
 * — caller-runs would push a Matrix HTTP call onto the request thread that just persisted a
 * notification, which is exactly the blocking this design avoids. The caller catches the rejection,
 * warns and counts it as {@code failed}; the 15 s poll remains the floor.
 */
@Configuration
public class MatrixFeedSignalExecutorConfig {

  static final int CORE_POOL_SIZE = 1;
  static final int MAX_POOL_SIZE = 2;
  static final int QUEUE_CAPACITY = 500;

  public static final String EXECUTOR_BEAN = "matrixFeedSignalExecutor";

  @Bean(name = EXECUTOR_BEAN, destroyMethod = "shutdown")
  public ThreadPoolTaskExecutor matrixFeedSignalExecutor() {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(CORE_POOL_SIZE);
    executor.setMaxPoolSize(MAX_POOL_SIZE);
    executor.setQueueCapacity(QUEUE_CAPACITY);
    executor.setThreadNamePrefix("feed-signal-");
    executor.setRejectedExecutionHandler(rejectedExecutionHandler());
    // Shed in-flight signals quickly on shutdown; they are best-effort, never worth delaying a
    // rolling restart for.
    executor.setWaitForTasksToCompleteOnShutdown(false);
    executor.setAwaitTerminationSeconds(5);
    executor.initialize();
    return executor;
  }

  static RejectedExecutionHandler rejectedExecutionHandler() {
    return new ThreadPoolExecutor.AbortPolicy();
  }
}
