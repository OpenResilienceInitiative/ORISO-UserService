package de.caritas.cob.userservice.api.service.handshake;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;

import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent;
import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent.OutboxStatus;
import de.caritas.cob.userservice.api.port.out.HandshakeOutboxEventRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Durable job runner for support access (ADR-018 §4). A job is claimed atomically in a short
 * transaction; the external work then happens outside any transaction, so no database lock is ever
 * held across a Keycloak or Matrix call.
 */
@Service
@Slf4j
public class HandshakeOutboxProcessor {

  private final HandshakeOutboxEventRepository outboxRepository;
  private final HandshakeOutboxCompletionService completionService;
  private final Map<String, SupportAccessJobHandler> handlersByType;

  @Value("${handshake.outbox.batch-size:20}")
  private int batchSize;

  public HandshakeOutboxProcessor(
      @NonNull HandshakeOutboxEventRepository outboxRepository,
      @NonNull HandshakeOutboxCompletionService completionService,
      @NonNull List<SupportAccessJobHandler> handlers) {
    this.outboxRepository = outboxRepository;
    this.completionService = completionService;
    this.handlersByType =
        handlers.stream()
            .collect(Collectors.toMap(SupportAccessJobHandler::jobType, Function.identity()));
  }

  @Scheduled(fixedDelayString = "${handshake.outbox.poll-delay-ms:5000}")
  public void processPending() {
    outboxRepository
        .findAllByStatusAndNextAttemptDateBeforeOrderById(
            OutboxStatus.PENDING, nowInUtc(), PageRequest.of(0, batchSize))
        .forEach(this::process);
  }

  private void process(HandshakeOutboxEvent event) {
    if (outboxRepository.claim(event.getId()) != 1) {
      // Another instance got there first.
      return;
    }
    var handler = handlersByType.get(event.getEventType());
    if (handler == null) {
      completionService.recordFailure(
          event.getId(),
          new IllegalStateException("No handler for job type " + event.getEventType()),
          false);
      return;
    }
    try {
      handler.handle(event.getAggregateId());
      completionService.markPublished(event.getId());
    } catch (RuntimeException exception) {
      log.warn(
          "Support access job {} for {} failed and will be retried",
          event.getEventType(),
          event.getAggregateId(),
          exception);
      completionService.recordFailure(event.getId(), exception, handler.retriesForever());
    }
  }
}
