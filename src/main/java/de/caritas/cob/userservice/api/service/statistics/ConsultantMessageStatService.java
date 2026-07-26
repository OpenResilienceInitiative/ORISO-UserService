package de.caritas.cob.userservice.api.service.statistics;

import de.caritas.cob.userservice.api.model.ConsultantMessageStat;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantMessageStatRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixEventIdentity;
import java.time.LocalDateTime;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
    recordMessageSent(consultantId, sessionId, null);
  }

  /**
   * Records a Matrix message once across retries and service replicas when an event id is
   * available. Legacy callers without an event id retain the historical append-only behaviour.
   */
  public void recordMessageSent(String consultantId, Long sessionId, String matrixEventId) {
    try {
      String sourceEventHash = MatrixEventIdentity.opaqueHash(matrixEventId);
      if (sourceEventHash != null
          && consultantMessageStatRepository.existsBySourceEventHash(sourceEventHash)) {
        return;
      }

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
              .sourceEventHash(sourceEventHash)
              .sentDate(LocalDateTime.now())
              .build();
      if (sourceEventHash == null) {
        consultantMessageStatRepository.save(stat);
      } else {
        consultantMessageStatRepository.saveAndFlush(stat);
      }
    } catch (DataIntegrityViolationException duplicate) {
      log.debug("Consultant message statistic already recorded for Matrix event");
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
