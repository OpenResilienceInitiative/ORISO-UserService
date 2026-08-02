package de.caritas.cob.userservice.api.service.handshake;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;

import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent;
import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent.OutboxStatus;
import de.caritas.cob.userservice.api.port.out.HandshakeOutboxEventRepository;
import de.caritas.cob.userservice.api.service.support.SupportAccessMatrixWorker;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the durable support-access jobs (ADR-018 §4).
 *
 * <p>Callers only ever enqueue a {@link SupportAccessJob}; everything that makes a job survive
 * failure — claiming it exactly once, keeping external calls outside any transaction, the capped
 * exponential backoff, and whether the job may ever be abandoned — lives behind this one class.
 *
 * <p>Claiming and bookkeeping run in their own short transactions, deliberately propagated as
 * REQUIRES_NEW, so that no database lock is ever held while Keycloak or Matrix is being called.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SupportAccessJobRunner {

  private static final int MAX_ERROR_LENGTH = 1000;
  private static final int MAX_BACKOFF_SHIFT = 10;

  private final @NonNull HandshakeOutboxEventRepository outboxRepository;
  private final @NonNull SupportAccessMatrixWorker worker;

  @Value("${handshake.outbox.batch-size:20}")
  private int batchSize;

  @Value("${handshake.outbox.max-attempts:5}")
  private int maxAttempts;

  @Value("${handshake.outbox.retry-base-seconds:30}")
  private long retryBaseSeconds;

  @Value("${handshake.outbox.retry-max-seconds:900}")
  private long retryMaxSeconds;

  @Scheduled(fixedDelayString = "${handshake.outbox.poll-delay-ms:5000}")
  public void processPending() {
    outboxRepository
        .findAllByStatusAndNextAttemptDateBeforeOrderById(
            OutboxStatus.PENDING, nowInUtc(), PageRequest.of(0, batchSize))
        .forEach(this::run);
  }

  private void run(HandshakeOutboxEvent event) {
    if (outboxRepository.claim(event.getId()) != 1) {
      // Another instance got there first.
      return;
    }
    SupportAccessJob job = null;
    try {
      job = SupportAccessJob.of(event.getEventType());
      execute(job, event.getAggregateId());
      markPublished(event.getId());
    } catch (RuntimeException exception) {
      log.warn(
          "Support access job {} for {} failed and will be retried",
          event.getEventType(),
          event.getAggregateId(),
          exception);
      recordFailure(event.getId(), exception, job != null && job.retriesForever());
    }
  }

  private void execute(SupportAccessJob job, String aggregateId) {
    switch (job) {
      case PROVISION_ROOM -> worker.provision(aggregateId);
      case REVOKE_ACCESS -> worker.revoke(aggregateId);
      case PURGE_CALL_ROOM -> worker.purgeCallRoom(aggregateId);
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void markPublished(Long eventId) {
    var event = require(eventId);
    if (event.getStatus() != OutboxStatus.PROCESSING) {
      throw new IllegalStateException("Handshake outbox completion has invalid state");
    }
    event.setStatus(OutboxStatus.PUBLISHED);
    event.setProcessedDate(nowInUtc());
    event.setLastError(null);
    outboxRepository.save(event);
  }

  /**
   * @param retriesForever withdrawal jobs must never end up FAILED — as long as a support identity
   *     might still reach a room, giving up would turn an outage into a false security claim.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void recordFailure(Long eventId, RuntimeException exception, boolean retriesForever) {
    var event = require(eventId);
    var attempts = event.getAttempts() + 1;
    event.setAttempts(attempts);
    event.setStatus(
        !retriesForever && attempts >= maxAttempts ? OutboxStatus.FAILED : OutboxStatus.PENDING);
    event.setLastError(safeMessage(exception));
    event.setNextAttemptDate(nowInUtc().plusSeconds(backoffSeconds(attempts)));
    outboxRepository.save(event);
  }

  private HandshakeOutboxEvent require(Long eventId) {
    return outboxRepository
        .findById(eventId)
        .orElseThrow(() -> new IllegalStateException("Handshake outbox event not found"));
  }

  private long backoffSeconds(int attempts) {
    var exponent = Math.min(attempts - 1, MAX_BACKOFF_SHIFT);
    return Math.min(retryBaseSeconds * (1L << exponent), retryMaxSeconds);
  }

  private String safeMessage(RuntimeException exception) {
    var message =
        exception.getMessage() == null
            ? exception.getClass().getSimpleName()
            : exception.getMessage();
    return StringUtils.abbreviate(message, MAX_ERROR_LENGTH);
  }
}
