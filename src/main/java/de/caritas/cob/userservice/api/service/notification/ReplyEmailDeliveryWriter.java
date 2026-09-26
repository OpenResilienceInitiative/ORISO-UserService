package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.model.ReplyEmailDelivery;
import de.caritas.cob.userservice.api.port.out.ReplyEmailDeliveryRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
  public long reserve(String recipientUserId, String eventKey, long tenantId, long sessionId) {
    var delivery = new ReplyEmailDelivery();
    delivery.setRecipientUserId(recipientUserId);
    delivery.setEventKey(eventKey);
    delivery.setTenantId(tenantId);
    delivery.setSessionId(sessionId);
    delivery.setStatus(ReplyEmailDelivery.Status.PENDING);
    delivery.setCreatedAt(LocalDateTime.now());
    delivery.setNextAttemptAt(delivery.getCreatedAt());
    return repository.saveAndFlush(delivery).getId();
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
    stale.forEach(delivery -> delivery.setStatus(ReplyEmailDelivery.Status.UNCERTAIN));
    return stale.size();
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
