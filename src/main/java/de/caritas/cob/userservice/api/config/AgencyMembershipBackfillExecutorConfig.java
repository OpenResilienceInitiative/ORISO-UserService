package de.caritas.cob.userservice.api.config;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Small pool for joining an agency's counsellors into a new enquiry room after the registration
 * request has answered.
 *
 * <p>Separate from the application-wide {@code taskExecutor} because its tasks deliberately sleep
 * for Synapse's {@code retry_after_ms} and may run for minutes; they must not starve unrelated
 * async work. Two threads keep two simultaneous enquiries from queueing behind each other while
 * staying far below what Synapse would throttle anyway.
 *
 * <p>{@link ThreadPoolExecutor.AbortPolicy}, never caller-runs: running the fan-out on the request
 * thread is exactly the 504 this pool exists to avoid. The caller logs the rejection.
 */
@Configuration
public class AgencyMembershipBackfillExecutorConfig {

  static final int POOL_SIZE = 2;
  static final int QUEUE_CAPACITY = 500;

  public static final String EXECUTOR_BEAN = "agencyMembershipBackfillExecutor";

  @Bean(name = EXECUTOR_BEAN, destroyMethod = "shutdown")
  public ThreadPoolTaskExecutor agencyMembershipBackfillExecutor(
      ObjectProvider<TenantContextProvider> tenantContextProvider) {
    var executor = new ThreadPoolTaskExecutor();
    executor.setTaskDecorator(
        tenantContextProvider.getIfAvailable(TenantContextProvider::new)::inCallersContext);
    executor.setCorePoolSize(POOL_SIZE);
    executor.setMaxPoolSize(POOL_SIZE);
    executor.setQueueCapacity(QUEUE_CAPACITY);
    executor.setThreadNamePrefix("dept-membership-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(false);
    executor.setAwaitTerminationSeconds(5);
    executor.initialize();
    return executor;
  }
}
