package de.caritas.cob.userservice.api.service.session;

import de.caritas.cob.userservice.api.model.DeletedAskerSession;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.DeletedAskerSessionRepository;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ORISO-Frontend#1159: when an advice seeker's session is deleted — the seeker deleted their
 * account or this one chat, or the account was deleted automatically — the counsellor used to lose
 * the case without a trace and could not tell a deletion from a technical problem. This service
 * leaves a placeholder without personal data in the counsellor's list and one timeline entry.
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

  @Value("${deleted.asker.session.retention.days:30}")
  private int retentionDays;

  /**
   * Records the placeholder for the counsellor the session was assigned to. Sessions nobody had
   * accepted yet have no counsellor and leave no placeholder.
   *
   * <p>Runs in its own transaction so a failure here can never mark the deletion's transaction
   * rollback-only. Idempotent: a retried deletion neither adds a second placeholder nor a second
   * timeline entry.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordDeletion(Session session) {
    if (session == null || session.getId() == null || session.getConsultant() == null) {
      return;
    }
    var consultantId = session.getConsultant().getId();
    if (consultantId == null || consultantId.isBlank()) {
      return;
    }
    try {
      if (!deletedAskerSessionRepository.existsByConsultantIdAndFormerSessionId(
          consultantId, session.getId())) {
        deletedAskerSessionRepository.save(
            DeletedAskerSession.builder()
                .consultantId(consultantId)
                .formerSessionId(session.getId())
                .agencyId(session.getAgencyId())
                .tenantId(session.getTenantId())
                .deletedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build());
      }
      eventNotificationService.createEventOnce(
          EVENT_TYPE + ":" + session.getId(),
          consultantId,
          EVENT_TYPE,
          EventNotificationService.CATEGORY_SYSTEM,
          "Deleted advice seeker",
          "An advice seeker's chat was deleted. Their data is no longer available.",
          null,
          null,
          null,
          session.getTenantId());
    } catch (RuntimeException ex) {
      log.warn("Could not record deleted-asker placeholder for session {}", session.getId(), ex);
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
