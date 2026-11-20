package de.caritas.cob.userservice.api.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated, small, bounded pool for the busy import from the counsellors' own calendars (ADR-032,
 * ORISO-UserService#1645).
 *
 * <p>The import reads calendars from hosts the counsellors name, so a run can take minutes. On
 * Boot's single shared scheduler thread it would hold up every other scheduled job meanwhile; here
 * the scheduled method only claims the run and hands it to this pool. One run puts one worker per
 * thread into the queue, so the queue holds one run that waits for the previous one; anything
 * beyond is rejected ({@link ThreadPoolExecutor.AbortPolicy}, never caller-runs, which would put
 * the import back onto the scheduler thread) and the next tick tries again.
 */
@Configuration
public class CalendarImportExecutorConfig {

  public static final String EXECUTOR_BEAN = "calendarImportExecutor";

  @Bean(name = EXECUTOR_BEAN, destroyMethod = "shutdown")
  public ThreadPoolTaskExecutor calendarImportExecutor(
      @Value("${calendar.import.threads:3}") int threads) {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(threads);
    executor.setMaxPoolSize(threads);
    executor.setQueueCapacity(threads);
    executor.setThreadNamePrefix("calendar-import-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    // An import cut off by a restart is repeated by the next run; it is not worth waiting for.
    executor.setWaitForTasksToCompleteOnShutdown(false);
    executor.setAwaitTerminationSeconds(5);
    executor.initialize();
    return executor;
  }
}
