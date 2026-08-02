package de.caritas.cob.userservice.api.service.handshake;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;

import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent.OutboxStatus;
import de.caritas.cob.userservice.api.port.out.HandshakeOutboxEventRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bookkeeping for outbox jobs, kept in its own short transaction so the external work in {@link
 * HandshakeOutboxProcessor} stays outside any database transaction.
 */
@Service
@RequiredArgsConstructor
public class HandshakeOutboxCompletionService {

  private final @NonNull HandshakeOutboxEventRepository outboxRepository;

  @Value("${handshake.outbox.max-attempts:5}")
  private int maxAttempts;

  @Value("${handshake.outbox.retry-base-seconds:30}")
  private long retryBaseSeconds;

  @Value("${handshake.outbox.retry-max-seconds:900}")
  private long retryMaxSeconds;

  @Transactional
  public void markPublished(Long eventId) {
    var event =
        outboxRepository
            .findById(eventId)
            .orElseThrow(() -> new IllegalStateException("Handshake outbox event not found"));
    if (event.getStatus() != OutboxStatus.PROCESSING) {
      throw new IllegalStateException("Handshake outbox completion has invalid state");
    }
    event.setStatus(OutboxStatus.PUBLISHED);
    event.setProcessedDate(nowInUtc());
    event.setLastError(null);
  }

  /**
   * @param retriesForever withdrawal jobs must never end up FAILED — as long as a support identity
   *     might still reach a room, giving up would turn an outage into a false security claim.
   */
  @Transactional
  public void recordFailure(Long eventId, RuntimeException exception, boolean retriesForever) {
    var event =
        outboxRepository
            .findById(eventId)
            .orElseThrow(() -> new IllegalStateException("Handshake outbox event not found"));
    var attempts = event.getAttempts() + 1;
    event.setAttempts(attempts);
    event.setStatus(
        !retriesForever && attempts >= maxAttempts ? OutboxStatus.FAILED : OutboxStatus.PENDING);
    event.setLastError(safeMessage(exception));
    event.setNextAttemptDate(nowInUtc().plusSeconds(backoffSeconds(attempts)));
  }

  private long backoffSeconds(int attempts) {
    var exponent = Math.min(attempts - 1, 10);
    return Math.min(retryBaseSeconds * (1L << exponent), retryMaxSeconds);
  }

  private String safeMessage(RuntimeException exception) {
    var message =
        exception.getMessage() == null
            ? exception.getClass().getSimpleName()
            : exception.getMessage();
    return message.substring(0, Math.min(message.length(), 1000));
  }
}
