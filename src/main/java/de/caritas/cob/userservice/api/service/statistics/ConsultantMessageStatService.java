package de.caritas.cob.userservice.api.service.statistics;

import de.caritas.cob.userservice.api.model.ConsultantMessageStat;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantMessageStatRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.time.LocalDateTime;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Records and counts "message sent" events for the consultant self-view statistics tab. No message
 * content is ever touched here (KDG compliance) and the consultant identity is stored only as a
 * pseudonymous HMAC (see {@link ConsultantIdentityHasher} / {@link ConsultantMessageStat}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsultantMessageStatService {

  private final @NonNull ConsultantMessageStatRepository consultantMessageStatRepository;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull ConsultantIdentityHasher consultantIdentityHasher;

  /**
   * Called once per Matrix message whose resolved sender is a consultant. Best-effort: a failure
   * here must never break the live-notification path it is called alongside, so exceptions are
   * logged and swallowed.
   */
  public void recordMessageSent(String consultantId, Long sessionId) {
    try {
      Long tenantId = null;
      Long agencyId = null;
      if (sessionId != null) {
        Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null) {
          tenantId = session.getTenantId();
          agencyId = session.getAgencyId();
        }
      }

      var stat =
          ConsultantMessageStat.builder()
              .consultantHmac(consultantIdentityHasher.hash(consultantId))
              .tenantId(tenantId)
              .agencyId(agencyId)
              .sourceSessionId(sessionId)
              .sentDate(LocalDateTime.now())
              .build();
      consultantMessageStatRepository.save(stat);
    } catch (Exception e) {
      log.error("Failed to record consultant message-sent statistic", e);
    }
  }

  /** Own-data-only: callers must pass the id/tenant of the authenticated consultant. */
  public long countForConsultant(
      String consultantId, Long tenantId, LocalDateTime fromDate, LocalDateTime toDate) {
    return consultantMessageStatRepository.countByConsultantHmacAndTenantIdInPeriod(
        consultantIdentityHasher.hash(consultantId), tenantId, fromDate, toDate);
  }
}
