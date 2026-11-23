package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.model.ReplyEmailDelivery;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.RecipientKind;
import de.caritas.cob.userservice.api.port.out.ReplyEmailDeliveryRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the unique claim transaction separate from Matrix event processing. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReplyEmailDeliveryWriter {
  /**
   * How long a failing preflight is retried before the row is quarantined. Long enough that a
   * platform outage recovers on its own, short enough that a broken tenant setup stops cycling.
   */
  static final Duration MAX_RETRY_AGE = Duration.ofHours(24);

  private static final int BATCH_SIZE = 100;

  private final @NonNull ReplyEmailDeliveryRepository repository;

  /** Rotation point when more tenants are due than fit one batch; per instance, best effort. */
  private final AtomicReference<Long> resumeAfterTenant = new AtomicReference<>();

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

  /** Tells a replayed internal intent apart from any other integrity failure. */
  @Transactional(readOnly = true)
  public boolean hasInternalIntent(String actorId, String eventKey) {
    return repository.existsByRecipientKindAndRecipientUserIdAndEventKey(
        RecipientKind.INTERNAL_INTENT, actorId, eventKey);
  }

  public boolean hasInternalRecipient(String recipientId, String eventKey) {
    return repository.existsByRecipientKindAndRecipientUserIdAndEventKey(
        RecipientKind.INTERNAL, recipientId, eventKey);
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

  /**
   * Due row ids, interleaved round-robin across tenants (oldest row of each tenant first), so one
   * tenant's backlog cannot fill the batch and hold back mail for every other tenant (#1449). Each
   * tenant is first asked only for its fair share; unused slots are then topped up from tenants
   * with more due rows. With more due tenants than slots, the next batch resumes after the last
   * tenant served, so no tenant waits behind the same hundred others forever.
   */
  @Transactional(readOnly = true)
  public List<Long> pendingIds() {
    var now = LocalDateTime.now();
    var due = repository.findDueTenantIds(ReplyEmailDelivery.Status.PENDING, now);
    if (due.isEmpty()) {
      return List.of();
    }
    var round = rotate(due);
    int quota = (BATCH_SIZE + round.size() - 1) / round.size();
    var perTenant = new LinkedHashMap<Long, List<Long>>();
    for (Long tenantId : round) {
      perTenant.put(tenantId, dueIds(tenantId, now, quota));
    }
    var batch = interleave(perTenant.values());
    for (var entry : perTenant.entrySet()) {
      if (batch.size() >= BATCH_SIZE) {
        break;
      }
      if (entry.getValue().size() == quota) {
        var more = dueIds(entry.getKey(), now, quota + BATCH_SIZE - batch.size());
        // Rows may have been claimed meanwhile; the set skips ids already in the batch.
        more.stream().skip(quota).limit(BATCH_SIZE - batch.size()).forEach(batch::add);
      }
    }
    return List.copyOf(batch);
  }

  private List<Long> rotate(List<Long> dueTenants) {
    Long resumeAfter = resumeAfterTenant.get();
    int start = 0;
    if (resumeAfter != null) {
      while (start < dueTenants.size() && dueTenants.get(start) <= resumeAfter) {
        start++;
      }
      start = start % dueTenants.size();
    }
    var round = new ArrayList<Long>(Math.min(BATCH_SIZE, dueTenants.size()));
    for (int i = 0; i < dueTenants.size() && round.size() < BATCH_SIZE; i++) {
      round.add(dueTenants.get((start + i) % dueTenants.size()));
    }
    resumeAfterTenant.set(dueTenants.size() > BATCH_SIZE ? round.get(round.size() - 1) : null);
    return round;
  }

  private List<Long> dueIds(Long tenantId, LocalDateTime now, int max) {
    return repository.findDueIds(ReplyEmailDelivery.Status.PENDING, tenantId, now, Limit.of(max));
  }

  private static LinkedHashSet<Long> interleave(Collection<List<Long>> perTenant) {
    var batch = new LinkedHashSet<Long>();
    var cursors = new ArrayList<Iterator<Long>>();
    perTenant.forEach(ids -> cursors.add(ids.iterator()));
    while (batch.size() < BATCH_SIZE && !cursors.isEmpty()) {
      var tenants = cursors.iterator();
      while (tenants.hasNext() && batch.size() < BATCH_SIZE) {
        var ids = tenants.next();
        if (ids.hasNext()) {
          batch.add(ids.next());
        } else {
          tenants.remove();
        }
      }
    }
    return batch;
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
    if (delivery.getCreatedAt().isBefore(LocalDateTime.now().minus(MAX_RETRY_AGE))) {
      // Bounded retries: a tenant whose mail setup stays broken must not cycle forever (#1449).
      delivery.setStatus(ReplyEmailDelivery.Status.QUARANTINED);
      log.error(
          "Reply-email delivery {} of tenant {} QUARANTINED after {} attempts in {}; fix the"
              + " tenant's notification setup, then requeue (docs/reply-email-quarantine.md)",
          id,
          delivery.getTenantId(),
          delivery.getAttemptCount(),
          MAX_RETRY_AGE);
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
  public long quarantinedCount() {
    return repository.countByStatus(ReplyEmailDelivery.Status.QUARANTINED);
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
