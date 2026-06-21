package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

class UserServiceApplicationTest {

  @Test
  void applicationPropertiesShouldEnableGracefulShutdown() throws Exception {
    Properties properties = new Properties();
    try (InputStream inputStream =
        new ClassPathResource("application.properties").getInputStream()) {
      properties.load(inputStream);
    }

    assertThat(properties.getProperty("server.shutdown")).isEqualTo("graceful");
    assertThat(properties.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
        .isEqualTo("30s");
  }

  @Test
  void taskExecutorShouldWaitForQueuedTasksOnShutdown() throws Exception {
    UserServiceApplication application = new UserServiceApplication();
    ReflectionTestUtils.setField(application, "THREAD_CORE_POOL_SIZE", 1);
    ReflectionTestUtils.setField(application, "THREAD_MAX_POOL_SIZE", 1);
    ReflectionTestUtils.setField(application, "THREAD_QUEUE_CAPACITY", 1);
    ReflectionTestUtils.setField(application, "THREAD_NAME_PREFIX", "test-");

    Executor executor = application.taskExecutor();

    assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
    ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) executor;
    CountDownLatch taskStarted = new CountDownLatch(1);
    CountDownLatch releaseTask = new CountDownLatch(1);
    AtomicBoolean shutdownReturned = new AtomicBoolean(false);

    taskExecutor.execute(
        () -> {
          taskStarted.countDown();
          try {
            releaseTask.await();
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
        });
    assertThat(taskStarted.await(1, TimeUnit.SECONDS)).isTrue();

    Thread shutdownThread =
        new Thread(
            () -> {
              taskExecutor.shutdown();
              shutdownReturned.set(true);
            });
    shutdownThread.start();

    TimeUnit.MILLISECONDS.sleep(100);
    assertThat(shutdownReturned).isFalse();
    releaseTask.countDown();
    shutdownThread.join(1000);
    assertThat(shutdownReturned).isTrue();
  }
}
