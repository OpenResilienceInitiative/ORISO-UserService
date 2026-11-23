package de.caritas.cob.userservice.api.service.matrix;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-recipient trailing-edge coalescing for the P2 feed-update signal (ADR-020).
 *
 * <p>A burst of feed rows for one recipient must cost one Matrix to-device message, not N. The
 * first row of a burst opens a window; every further row inside that window is absorbed by the send
 * already scheduled for the end of it. Trailing rather than leading edge on purpose: the signal
 * then covers every row of the burst, so the client's single refresh sees all of them instead of
 * refreshing on row 1 and waiting for the 15 s poll to discover rows 2..N.
 *
 * <p>Pure and thread-safe: no clock, no threads, no I/O — the caller passes {@code nowMillis} and
 * performs the scheduling. That keeps it deterministically unit-testable.
 */
public class FeedSignalCoalescer {

  /** Safety bound on tracked recipients; only expired entries are ever dropped. */
  static final int MAX_TRACKED_RECIPIENTS = 10_000;

  private final long windowMillis;
  private final Map<String, Long> windowEndByRecipient = new ConcurrentHashMap<>();

  public FeedSignalCoalescer(long windowMillis) {
    this.windowMillis = Math.max(windowMillis, 0L);
  }

  /** What the caller must do with the row it just persisted. */
  public enum Decision {
    /** No window was open: schedule one send at {@link Window#endMillis()}. */
    SCHEDULE,
    /** A send is already scheduled for this recipient and will cover this row. */
    COALESCE
  }

  /** The outcome of {@link #recordAndDecide}: what to do, and when the scheduled send is due. */
  public record Window(Decision decision, long endMillis) {}

  public long windowMillis() {
    return windowMillis;
  }

  public Window recordAndDecide(String recipientUserId, long nowMillis) {
    pruneIfOversized(nowMillis);

    // One atomic decision per recipient: compute() runs the remapping under the bin lock, so
    // concurrent rows for the same recipient cannot both decide to schedule.
    var decision = new Decision[1];
    long endMillis =
        windowEndByRecipient.compute(
            recipientUserId,
            (recipient, openWindowEnd) -> {
              if (openWindowEnd != null && openWindowEnd > nowMillis) {
                decision[0] = Decision.COALESCE;
                // Keep the ORIGINAL end: a steady trickle must not push the send out forever.
                return openWindowEnd;
              }
              decision[0] = Decision.SCHEDULE;
              return nowMillis + windowMillis;
            });

    return new Window(decision[0], endMillis);
  }

  /** Closes the window once its scheduled send has fired, so the next row opens a fresh one. */
  public void releaseWindow(String recipientUserId, long endMillis) {
    // Remove only if unchanged: a late release for a superseded window must not reopen the
    // current one.
    windowEndByRecipient.remove(recipientUserId, endMillis);
  }

  /**
   * Drops already-expired windows, and only those, once the map grows past its safety bound. An
   * expired entry carries no state — the next row for that recipient opens a fresh window either
   * way — so this can never turn a COALESCE into a duplicate send.
   */
  private void pruneIfOversized(long nowMillis) {
    if (windowEndByRecipient.size() <= MAX_TRACKED_RECIPIENTS) {
      return;
    }
    windowEndByRecipient.entrySet().removeIf(entry -> entry.getValue() <= nowMillis);
  }

  int trackedRecipients() {
    return windowEndByRecipient.size();
  }
}
