package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.config.MatrixFeedSignalExecutorConfig;
import de.caritas.cob.userservice.api.config.observability.FeedSignalMetrics;
import de.caritas.cob.userservice.api.config.observability.FeedSignalMetrics.Outcome;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.matrix.FeedSignalCoalescer.Decision;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * P2 feed-update signal (ADR-020): tells a recipient's clients that their persisted
 * Activity-Timeline feed has changed, so they refresh immediately instead of on the next 15 s poll.
 *
 * <p>Successor to the hook {@code 8b75eddd} added to {@code LiveEventNotificationService} and that
 * {@code c62ae561} removed with the dead LiveService transport. Same contract, new transport:
 *
 * <ul>
 *   <li><b>Content-free.</b> The signal carries an empty Matrix event content. It says only "your
 *       feed changed"; every visible string is still read from the persisted feed over the
 *       authenticated REST endpoint. This keeps the ADR-AT-01 / FE-H01 privacy boundary intact.
 *   <li><b>Never blocks the caller.</b> The Matrix call runs on a small bounded pool ({@link
 *       MatrixFeedSignalExecutorConfig}). A saturated pool drops the signal with a warning rather
 *       than running it on the request thread.
 *   <li><b>After commit.</b> When a transaction is active everything is deferred to {@code
 *       afterCommit}, so a client can never refresh into a row that is not visible yet — and a
 *       rolled-back transaction sends nothing at all.
 *   <li><b>Coalesced per recipient.</b> A burst of rows for one recipient costs one to-device
 *       message at the end of a {@code matrix.feedSignal.coalesceMillis} window, not one per row.
 *   <li><b>Best effort.</b> A Matrix outage, a missing chat identity or an unresolvable recipient
 *       must never break the flow that created the notification. The 15 s poll remains the floor.
 *   <li><b>Switchable.</b> {@code matrix.feedSignal.enabled=false} disables it without a rollback.
 * </ul>
 */
@Slf4j
@Service
public class MatrixFeedUpdateSignalService {

  /** Content-free custom event type the frontend's matrixLiveEventBridge listens for. */
  public static final String FEED_UPDATE_EVENT_TYPE = "org.oriso.feed.updated";

  private final MatrixSynapseService matrixSynapseService;
  private final UserRepository userRepository;
  private final ConsultantRepository consultantRepository;
  private final FeedSignalMetrics metrics;
  private final FeedSignalDelayScheduler delayScheduler;
  private final Executor executor;
  private final boolean enabled;
  private final FeedSignalCoalescer coalescer;
  private final LongSupplier nowMillis;

  public MatrixFeedUpdateSignalService(
      MatrixSynapseService matrixSynapseService,
      UserRepository userRepository,
      ConsultantRepository consultantRepository,
      FeedSignalMetrics metrics,
      FeedSignalDelayScheduler delayScheduler,
      @Qualifier(MatrixFeedSignalExecutorConfig.EXECUTOR_BEAN) Executor executor,
      @Value("${matrix.feedSignal.enabled:true}") boolean enabled,
      @Value("${matrix.feedSignal.coalesceMillis:500}") long coalesceMillis) {
    this(
        matrixSynapseService,
        userRepository,
        consultantRepository,
        metrics,
        delayScheduler,
        executor,
        enabled,
        coalesceMillis,
        System::currentTimeMillis);
  }

  // Package-private constructor allowing tests to inject a controllable clock.
  MatrixFeedUpdateSignalService(
      MatrixSynapseService matrixSynapseService,
      UserRepository userRepository,
      ConsultantRepository consultantRepository,
      FeedSignalMetrics metrics,
      FeedSignalDelayScheduler delayScheduler,
      Executor executor,
      boolean enabled,
      long coalesceMillis,
      LongSupplier nowMillis) {
    this.matrixSynapseService = matrixSynapseService;
    this.userRepository = userRepository;
    this.consultantRepository = consultantRepository;
    this.metrics = metrics;
    this.delayScheduler = delayScheduler;
    this.executor = executor;
    this.enabled = enabled;
    this.coalescer = new FeedSignalCoalescer(coalesceMillis);
    this.nowMillis = nowMillis;
  }

  /**
   * Nudges one recipient's clients to refresh their notification feed.
   *
   * <p>Returns immediately in every case. Nothing here is allowed to throw.
   *
   * @param recipientUserId the ORISO user id of a consultant or an advice seeker
   */
  public void signalFeedUpdated(String recipientUserId) {
    if (recipientUserId == null || recipientUserId.isBlank()) {
      return;
    }
    if (!enabled) {
      metrics.record(Outcome.DISABLED);
      return;
    }

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      // Defer: the feed row is not visible to the reading connection before commit, and a
      // rolled-back transaction must not nudge anyone — nor consume a coalescing window.
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              openWindowOrCoalesce(recipientUserId);
            }
          });
      return;
    }

    openWindowOrCoalesce(recipientUserId);
  }

  private void openWindowOrCoalesce(String recipientUserId) {
    try {
      var now = nowMillis.getAsLong();
      var window = coalescer.recordAndDecide(recipientUserId, now);
      if (window.decision() == Decision.COALESCE) {
        // A send is already scheduled for this recipient and will cover this row too.
        metrics.record(Outcome.COALESCED);
        return;
      }
      delayScheduler.schedule(
          () -> handOffToExecutor(recipientUserId, window.endMillis()), window.endMillis() - now);
    } catch (Exception ex) {
      log.warn("Could not schedule feed-update signal: {}", ex.getMessage());
      metrics.record(Outcome.FAILED);
    }
  }

  private void handOffToExecutor(String recipientUserId, long windowEndMillis) {
    // Close the window first: rows arriving from here on belong to the next signal.
    coalescer.releaseWindow(recipientUserId, windowEndMillis);
    try {
      executor.execute(() -> sendSignal(recipientUserId));
    } catch (RejectedExecutionException rejected) {
      // Bounded pool saturated. Drop with a warning — never run a Matrix call on the caller.
      log.warn("Feed-signal executor saturated, dropping one signal: {}", rejected.getMessage());
      metrics.record(Outcome.FAILED);
    } catch (Exception ex) {
      log.warn("Could not hand off feed-update signal: {}", ex.getMessage());
      metrics.record(Outcome.FAILED);
    }
  }

  private void sendSignal(String recipientUserId) {
    try {
      var matrixUserId = resolveMatrixUserId(recipientUserId);
      if (matrixUserId == null || matrixUserId.isBlank()) {
        // No chat identity provisioned yet; there is nobody to signal.
        metrics.record(Outcome.SKIPPED_NO_IDENTITY);
        return;
      }
      // Empty content on purpose: "your feed changed", nothing about what changed.
      var accepted =
          matrixSynapseService.sendToDeviceMessage(FEED_UPDATE_EVENT_TYPE, matrixUserId, Map.of());
      metrics.record(accepted ? Outcome.SENT : Outcome.FAILED);
    } catch (Exception ex) {
      // Best effort: the 15 s poll remains the guaranteed floor.
      log.warn("Could not send feed-update signal: {}", ex.getMessage());
      metrics.record(Outcome.FAILED);
    }
  }

  /** A recipient id is either a consultant id or an advice seeker's user id. */
  private String resolveMatrixUserId(String recipientUserId) {
    return consultantRepository
        .findByIdAndDeleteDateIsNull(recipientUserId)
        .map(consultant -> consultant.getMatrixUserId())
        .filter(id -> id != null && !id.isBlank())
        .or(
            () ->
                userRepository
                    .findByUserIdAndDeleteDateIsNull(recipientUserId)
                    .map(user -> user.getMatrixUserId()))
        .orElse(null);
  }
}
