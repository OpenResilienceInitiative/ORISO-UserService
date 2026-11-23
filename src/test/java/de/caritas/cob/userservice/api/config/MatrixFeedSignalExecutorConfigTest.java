package de.caritas.cob.userservice.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** ADR-020: the feed-signal pool must be small, bounded, and must never borrow the caller. */
class MatrixFeedSignalExecutorConfigTest {

  @Test
  void poolIsSmallAndBounded() {
    var executor = new MatrixFeedSignalExecutorConfig().matrixFeedSignalExecutor();
    try {
      assertThat(executor.getCorePoolSize()).isEqualTo(1);
      assertThat(executor.getMaxPoolSize()).isEqualTo(2);
      assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(500);
    } finally {
      executor.shutdown();
    }
  }

  @Test
  void rejectionPolicyIsAbortNotCallerRunsSoTheRequestThreadIsNeverBorrowed() {
    // CallerRunsPolicy would run a Matrix HTTP call on the thread that persisted the
    // notification — the exact blocking this design exists to avoid.
    assertThat(MatrixFeedSignalExecutorConfig.rejectedExecutionHandler())
        .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class)
        .isNotInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
  }

  @Test
  void aSaturatedPoolRejectsInsteadOfRunningOnOrBlockingTheCaller() throws Exception {
    var executor = new MatrixFeedSignalExecutorConfig().matrixFeedSignalExecutor();
    var release = new CountDownLatch(1);
    var firstTaskRunning = new CountDownLatch(1);
    var callerThread = Thread.currentThread();
    var ranOnCaller = new AtomicReference<>(false);
    Runnable blockUntilReleased =
        () -> {
          firstTaskRunning.countDown();
          if (Thread.currentThread() == callerThread) {
            ranOnCaller.set(true);
          }
          try {
            release.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        };
    try {
      executor.execute(blockUntilReleased);
      assertThat(firstTaskRunning.await(5, TimeUnit.SECONDS)).isTrue();

      // Every further task either queues or occupies the one extra worker. A JDK pool only
      // grows past the core size once the queue is full, so saturate by submitting until it
      // refuses — capped well above core + queue + max so a hang is impossible.
      RejectedExecutionException rejection = null;
      var submitted = 0;
      var ceiling =
          MatrixFeedSignalExecutorConfig.QUEUE_CAPACITY
              + MatrixFeedSignalExecutorConfig.MAX_POOL_SIZE
              + 10;
      while (submitted < ceiling && rejection == null) {
        try {
          executor.execute(blockUntilReleased);
          submitted++;
        } catch (RejectedExecutionException rejected) {
          rejection = rejected;
        }
      }

      // It refused rather than blocking the caller or running the task inline.
      assertThat(rejection).isNotNull();
      assertThat(submitted).isBetween(MatrixFeedSignalExecutorConfig.QUEUE_CAPACITY, ceiling);
      assertThat(ranOnCaller.get()).isFalse();
    } finally {
      release.countDown();
      executor.shutdown();
    }
  }
}
