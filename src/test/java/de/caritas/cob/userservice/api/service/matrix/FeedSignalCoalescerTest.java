package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.service.matrix.FeedSignalCoalescer.Decision;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Pure, clock-free unit tests for the per-recipient coalescing window (ADR-020). */
class FeedSignalCoalescerTest {

  private static final String ALICE = "alice-id";
  private static final String BOB = "bob-id";
  private static final long WINDOW = 500L;

  @Test
  void firstRowOfABurstOpensAWindowAndSchedulesOneSend() {
    var coalescer = new FeedSignalCoalescer(WINDOW);

    var window = coalescer.recordAndDecide(ALICE, 1_000L);

    assertThat(window.decision()).isEqualTo(Decision.SCHEDULE);
    assertThat(window.endMillis()).isEqualTo(1_500L);
  }

  @Test
  void aBurstOfManyRowsForOneRecipientCostsExactlyOneSend() {
    var coalescer = new FeedSignalCoalescer(WINDOW);

    var scheduled = 0;
    for (long offset = 0; offset < WINDOW; offset += 50) {
      if (coalescer.recordAndDecide(ALICE, 1_000L + offset).decision() == Decision.SCHEDULE) {
        scheduled++;
      }
    }

    assertThat(scheduled).isEqualTo(1);
  }

  @Test
  void coalescedRowsKeepTheOriginalWindowEndSoTheSendIsNotPushedOut() {
    var coalescer = new FeedSignalCoalescer(WINDOW);
    coalescer.recordAndDecide(ALICE, 1_000L);

    var later = coalescer.recordAndDecide(ALICE, 1_400L);

    assertThat(later.decision()).isEqualTo(Decision.COALESCE);
    // Still the first window's end: a steady trickle must not starve the signal forever.
    assertThat(later.endMillis()).isEqualTo(1_500L);
  }

  @Test
  void recipientsDoNotShareAWindow() {
    var coalescer = new FeedSignalCoalescer(WINDOW);

    var alice = coalescer.recordAndDecide(ALICE, 1_000L);
    var bob = coalescer.recordAndDecide(BOB, 1_010L);

    assertThat(alice.decision()).isEqualTo(Decision.SCHEDULE);
    assertThat(bob.decision()).isEqualTo(Decision.SCHEDULE);
  }

  @Test
  void aRowAfterTheWindowExpiredOpensANewWindow() {
    var coalescer = new FeedSignalCoalescer(WINDOW);
    coalescer.recordAndDecide(ALICE, 1_000L);

    var afterExpiry = coalescer.recordAndDecide(ALICE, 1_500L);

    assertThat(afterExpiry.decision()).isEqualTo(Decision.SCHEDULE);
    assertThat(afterExpiry.endMillis()).isEqualTo(2_000L);
  }

  @Test
  void releasingAFiredWindowLetsTheNextRowScheduleImmediately() {
    var coalescer = new FeedSignalCoalescer(WINDOW);
    var first = coalescer.recordAndDecide(ALICE, 1_000L);

    coalescer.releaseWindow(ALICE, first.endMillis());
    var next = coalescer.recordAndDecide(ALICE, 1_200L);

    assertThat(next.decision()).isEqualTo(Decision.SCHEDULE);
    assertThat(coalescer.trackedRecipients()).isEqualTo(1);
  }

  @Test
  void releasingAWindowThatWasAlreadyReplacedLeavesTheNewOneIntact() {
    var coalescer = new FeedSignalCoalescer(WINDOW);
    var first = coalescer.recordAndDecide(ALICE, 1_000L);
    coalescer.releaseWindow(ALICE, first.endMillis());
    var second = coalescer.recordAndDecide(ALICE, 1_200L);

    // A late release for the *previous* window must not open the door for the current one.
    coalescer.releaseWindow(ALICE, first.endMillis());

    assertThat(coalescer.recordAndDecide(ALICE, 1_300L).decision()).isEqualTo(Decision.COALESCE);
    assertThat(second.endMillis()).isEqualTo(1_700L);
  }

  @Test
  void aZeroWindowDisablesCoalescingWithoutBreaking() {
    var coalescer = new FeedSignalCoalescer(0L);

    assertThat(coalescer.recordAndDecide(ALICE, 1_000L).decision()).isEqualTo(Decision.SCHEDULE);
    assertThat(coalescer.recordAndDecide(ALICE, 1_000L).decision()).isEqualTo(Decision.SCHEDULE);
  }

  @Test
  void aNegativeWindowIsClampedToZeroRatherThanSchedulingInThePast() {
    var coalescer = new FeedSignalCoalescer(-1L);

    assertThat(coalescer.windowMillis()).isZero();
    assertThat(coalescer.recordAndDecide(ALICE, 1_000L).endMillis()).isEqualTo(1_000L);
  }

  @Test
  void trackedRecipientsStayBoundedUnderManyExpiredWindows() {
    var coalescer = new FeedSignalCoalescer(WINDOW);

    for (int i = 0; i < FeedSignalCoalescer.MAX_TRACKED_RECIPIENTS + 500; i++) {
      // Every entry is already expired by the time the next one arrives.
      coalescer.recordAndDecide("recipient-" + i, 1_000L + (i * (WINDOW + 1)));
    }

    assertThat(coalescer.trackedRecipients())
        .isLessThanOrEqualTo(FeedSignalCoalescer.MAX_TRACKED_RECIPIENTS);
  }

  @Test
  void concurrentRowsForOneRecipientStillScheduleExactlyOnce() throws Exception {
    var coalescer = new FeedSignalCoalescer(WINDOW);
    var threads = 16;
    var start = new CountDownLatch(1);
    var done = new CountDownLatch(threads);
    var scheduled = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(threads);
    try {
      for (int i = 0; i < threads; i++) {
        executor.submit(
            () -> {
              try {
                start.await();
                if (coalescer.recordAndDecide(ALICE, 1_000L).decision() == Decision.SCHEDULE) {
                  scheduled.incrementAndGet();
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      executor.shutdownNow();
    }

    assertThat(scheduled).hasValue(1);
    assertThat(new ConcurrentHashMap<>()).isEmpty();
  }
}
