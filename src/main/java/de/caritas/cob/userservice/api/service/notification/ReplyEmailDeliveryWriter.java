package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.model.ReplyEmailDelivery;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.RecipientKind;
import de.caritas.cob.userservice.api.port.out.ReplyEmailDeliveryRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the unique claim transaction separate from Matrix event processing. */
@Service
@RequiredArgsConstructor
public class ReplyEmailDeliveryWriter {
  private final @NonNull ReplyEmailDeliveryRepository repository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public long reserve(
      RecipientKind recipientKind,
      String recipientUserId,
      String sourceMatrixUserId,
      String sourceRoomId,
      String eventKey,
      long tenantId,
      long sessionId) {
    return saveNew(
        recipientKind,
        recipientUserId,
        sourceMatrixUserId,
        sourceRoomId,
        null,
        eventKey,
        tenantId,
        sessionId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public long reserveFeedbackIntent(
      String actorId,
      String actorMatrixUserId,
      String sourceRoomId,
      String sourceEventId,
      String eventKey,
      long tenantId,
      long sessionId) {
    return saveNew(
        RecipientKind.FEEDBACK_INTENT,
        actorId,
        actorMatrixUserId,
        sourceRoomId,
        sourceEventId,
        eventKey,
        tenantId,
        sessionId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public long reserveFeedbackRecipient(String recipientId, ReplyEmailDelivery intent) {
    return saveNew(
        RecipientKind.FEEDBACK,
        recipientId,
        intent.getSourceMatrixUserId(),
        intent.getSourceRoomId(),
        intent.getSourceEventId(),
        intent.getEventKey(),
        intent.getTenantId(),
        intent.getSessionId());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public long reserveInternalIntent(
      String actorId,
      String matrixActorId,
      String roomId,
      String eventId,
      String eventKey,
      long tenantId,
      long sessionId) {
    return saveNew(
        RecipientKind.INTERNAL_INTENT,
        actorId,
        matrixActorId,
        roomId,
        eventId,
        eventKey,
        tenantId,
        sessionId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public long reserveInternalRecipient(String recipientId, ReplyEmailDelivery intent) {
    return saveNew(
        RecipientKind.INTERNAL,
        recipientId,
        intent.getSourceMatrixUserId(),
        intent.getSourceRoomId(),
        intent.getSourceEventId(),
        intent.getEventKey(),
        intent.getTenantId(),
        intent.getSessionId());
  }

  private long saveNew(
      RecipientKind recipientKind,
      String recipientUserId,
      String sourceMatrixUserId,
      String sourceRoomId,
      String sourceEventId,
      String eventKey,
      long tenantId,
      long sessionId) {
    var delivery = new ReplyEmailDelivery();
    delivery.setRecipientKind(recipientKind);
    delivery.setRecipientUserId(recipientUserId);
    delivery.setSourceMatrixUserId(sourceMatrixUserId);
    delivery.setSourceRoomId(sourceRoomId);
    delivery.setSourceEventId(sourceEventId);
    delivery.setEventKey(eventKey);
    delivery.setCorrelationId(UUID.randomUUID().toString());
    delivery.setTenantId(tenantId);
    delivery.setSessionId(sessionId);
    delivery.setStatus(ReplyEmailDelivery.Status.PENDING);
    delivery.setCreatedAt(LocalDateTime.now());
    delivery.setNextAttemptAt(delivery.getCreatedAt());
    return repository.saveAndFlush(delivery).getId();
  }

  @Transactional(readOnly = true)
  public Optional<RecipientKind> kindOf(long id) {
    return repository.findById(id).map(ReplyEmailDelivery::getRecipientKind);
  }

  @Transactional(readOnly = true)
  public List<Long> pendingIds() {
    return repository
        .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            ReplyEmailDelivery.Status.PENDING, LocalDateTime.now())
        .stream()
        .map(ReplyEmailDelivery::getId)
        .toList();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<ReplyEmailDelivery> claim(long id) {
    var delivery = repository.findByIdForUpdate(id).orElse(null);
    if (delivery == null
        || delivery.getStatus() != ReplyEmailDelivery.Status.PENDING
        || delivery.getNextAttemptAt().isAfter(LocalDateTime.now())) {
      return Optional.empty();
    }
    delivery.setStatus(ReplyEmailDelivery.Status.SENDING);
    delivery.setAttemptedAt(LocalDateTime.now());
    delivery.setAttemptCount(delivery.getAttemptCount() + 1);
    repository.saveAndFlush(delivery);
    return Optional.of(delivery);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void retryLater(long id) {
    var delivery = repository.findById(id).orElseThrow();
    if (delivery.getStatus() != ReplyEmailDelivery.Status.SENDING) {
      return;
    }
    delivery.setStatus(ReplyEmailDelivery.Status.PENDING);
    delivery.setNextAttemptAt(LocalDateTime.now().plusMinutes(5));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int markStaleSendingUncertain(Duration age) {
    var stale =
        repository.findByStatusAndAttemptedAtBefore(
            ReplyEmailDelivery.Status.SENDING, LocalDateTime.now().minus(age));
    int uncertain = 0;
    for (ReplyEmailDelivery delivery : stale) {
      if (delivery.getRecipientKind() == RecipientKind.FEEDBACK_INTENT
          || delivery.getRecipientKind() == RecipientKind.INTERNAL_INTENT) {
        // Resolving an intent does not enter SMTP. A crash can safely re-run its idempotent
        // fan-out.
        delivery.setStatus(ReplyEmailDelivery.Status.PENDING);
        delivery.setNextAttemptAt(LocalDateTime.now());
      } else {
        delivery.setStatus(ReplyEmailDelivery.Status.UNCERTAIN);
        uncertain++;
      }
    }
    return uncertain;
  }

  @Transactional(readOnly = true)
  public long uncertainCount() {
    return repository.countByStatus(ReplyEmailDelivery.Status.UNCERTAIN);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(long id, ReplyEmailDelivery.Status status) {
    var delivery = repository.findById(id).orElseThrow();
    delivery.setStatus(status);
    if (status == ReplyEmailDelivery.Status.SENT) {
      delivery.setSentAt(LocalDateTime.now());
    }
  }
}
