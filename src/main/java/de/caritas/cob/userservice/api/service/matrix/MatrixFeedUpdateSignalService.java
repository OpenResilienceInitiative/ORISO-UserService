package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import lombok.extern.slf4j.Slf4j;
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
 *   <li><b>Best effort.</b> A Matrix outage, a missing chat identity or an unresolvable recipient
 *       must never break the flow that created the notification. Everything is caught here; the 15
 *       s poll remains the guaranteed floor.
 *   <li><b>After commit.</b> When a transaction is active the signal is deferred to {@code
 *       afterCommit}, so a client can never refresh into a row that is not visible yet — and a
 *       rolled-back transaction sends nothing at all.
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
  private final boolean enabled;

  public MatrixFeedUpdateSignalService(
      MatrixSynapseService matrixSynapseService,
      UserRepository userRepository,
      ConsultantRepository consultantRepository,
      @Value("${matrix.feedSignal.enabled:true}") boolean enabled) {
    this.matrixSynapseService = matrixSynapseService;
    this.userRepository = userRepository;
    this.consultantRepository = consultantRepository;
    this.enabled = enabled;
  }

  /**
   * Nudges one recipient's clients to refresh their notification feed.
   *
   * @param recipientUserId the ORISO user id of a consultant or an advice seeker
   */
  public void signalFeedUpdated(String recipientUserId) {
    if (!enabled || recipientUserId == null || recipientUserId.isBlank()) {
      return;
    }

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      // Defer: the feed row is not visible to the reading connection before commit, and a
      // rolled-back transaction must not nudge anyone.
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              sendSignal(recipientUserId);
            }
          });
      return;
    }

    sendSignal(recipientUserId);
  }

  private void sendSignal(String recipientUserId) {
    try {
      var matrixUserId = resolveMatrixUserId(recipientUserId);
      if (matrixUserId == null || matrixUserId.isBlank()) {
        log.debug(
            "No Matrix identity for recipient {}; skipping feed-update signal", recipientUserId);
        return;
      }
      // Empty content on purpose: "your feed changed", nothing about what changed.
      matrixSynapseService.sendToDeviceMessage(
          FEED_UPDATE_EVENT_TYPE, matrixUserId, java.util.Map.of());
    } catch (Exception ex) {
      // Best effort: the 15 s poll remains the guaranteed floor.
      log.warn("Could not send feed-update signal to {}: {}", recipientUserId, ex.getMessage());
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
