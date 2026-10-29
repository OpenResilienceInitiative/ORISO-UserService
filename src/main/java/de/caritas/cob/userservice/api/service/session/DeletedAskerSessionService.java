package de.caritas.cob.userservice.api.service.session;

import de.caritas.cob.userservice.api.model.DeletedAskerSession;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.DeletedAskerSessionRepository;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ORISO-Frontend#1159: when an advice seeker's session is deleted — the seeker deleted their
 * account or this one chat, the account was deleted automatically or by an admin, or a counsellor
 * deleted the chat — the counsellor used to lose the case without a trace and could not tell a
 * deletion from a technical problem. This service leaves a placeholder without personal data in the
 * counsellor's list and one timeline entry.
 *
 * <p>Best-effort: a missing placeholder must never stop a deletion, which is a data-protection
 * obligation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeletedAskerSessionService {

  public static final String EVENT_TYPE = "session.asker_deleted";

  private final @NonNull DeletedAskerSessionRepository deletedAskerSessionRepository;
  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull PlatformTransactionManager transactionManager;

  @Value("${deleted.asker.session.retention.days:30}")
  private int retentionDays;

  /**
   * What the placeholder keeps of a session, captured while the session still exists. Sessions
   * nobody had accepted yet have no counsellor and leave no placeholder.
   */
  public record Placeholder(
      String consultantId, Long formerSessionId, Long agencyId, Long tenantId) {

    public static Optional<Placeholder> of(Session session) {
      if (session == null || session.getId() == null || session.getConsultant() == null) {
        return Optional.empty();
      }
      var consultantId = session.getConsultant().getId();
      if (consultantId == null || consultantId.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(
          new Placeholder(
              consultantId, session.getId(), session.getAgencyId(), session.getTenantId()));
    }
  }

  /**
   * Records the placeholder once the surrounding deletion has committed, or right away when there
   * is no transaction. A deletion that rolls back therefore never leaves a placeholder next to a
   * chat that still exists.
   */
  public void recordDeletionAfterCommit(Placeholder placeholder) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      recordDeletion(placeholder);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            recordDeletion(placeholder);
          }
        });
  }

  /**
   * Saves the placeholder and creates the timeline entry, each in its own transaction so that a
   * failure of one neither rolls back the other nor reaches the caller. Idempotent: a retried
   * deletion neither adds a second placeholder nor a second timeline entry.
   */
  void recordDeletion(Placeholder placeholder) {
    if (placeholder == null) {
      return;
    }
    inOwnTransaction(
        placeholder,
        "placeholder",
        status -> {
          if (!deletedAskerSessionRepository.existsByConsultantIdAndFormerSessionId(
              placeholder.consultantId(), placeholder.formerSessionId())) {
            deletedAskerSessionRepository.save(
                DeletedAskerSession.builder()
                    .consultantId(placeholder.consultantId())
                    .formerSessionId(placeholder.formerSessionId())
                    .agencyId(placeholder.agencyId())
                    .tenantId(placeholder.tenantId())
                    .deletedAt(LocalDateTime.now(ZoneOffset.UTC))
                    .build());
          }
        });
    inOwnTransaction(
        placeholder,
        "timeline entry",
        status ->
            eventNotificationService.createEventOnce(
                EVENT_TYPE + ":" + placeholder.formerSessionId(),
                placeholder.consultantId(),
                EVENT_TYPE,
                EventNotificationService.CATEGORY_SYSTEM,
                "Deleted advice seeker",
                "An advice seeker's chat was deleted. Their data is no longer available.",
                null,
                null,
                null,
                placeholder.tenantId()));
  }

  private void inOwnTransaction(
      Placeholder placeholder, String what, Consumer<TransactionStatus> work) {
    var transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    try {
      transaction.executeWithoutResult(work);
    } catch (RuntimeException ex) {
      log.warn(
          "Could not record deleted-asker {} for session {}",
          what,
          placeholder.formerSessionId(),
          ex);
    }
  }

  /** The counsellor's placeholders from within the retention period, newest first. */
  @Transactional(readOnly = true)
  public List<DeletedAskerSession> findForConsultant(String consultantId) {
    if (consultantId == null || consultantId.isBlank()) {
      return List.of();
    }
    return deletedAskerSessionRepository.findByConsultantIdAndDeletedAtAfterOrderByDeletedAtDesc(
        consultantId, LocalDateTime.now(ZoneOffset.UTC).minusDays(Math.max(retentionDays, 0)));
  }

  /**
   * Deletes placeholders older than the retention period. A non-positive setting disables the purge
   * rather than deleting everything.
   */
  @Transactional
  public int purgeExpired() {
    if (retentionDays <= 0) {
      return 0;
    }
    int purged =
        deletedAskerSessionRepository.deleteDeletedBefore(
            LocalDateTime.now(ZoneOffset.UTC).minusDays(retentionDays));
    if (purged > 0) {
      log.info("Purged {} deleted-asker placeholders older than {} days", purged, retentionDays);
    }
    return purged;
  }
}
