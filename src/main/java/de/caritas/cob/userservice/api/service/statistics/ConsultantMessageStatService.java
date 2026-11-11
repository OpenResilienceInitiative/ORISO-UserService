package de.caritas.cob.userservice.api.service.statistics;

import de.caritas.cob.userservice.api.model.ConsultantMessageStat;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantMessageStatRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSettingsService;
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
  private final @NonNull StatisticsSettingsService statisticsSettingsService;
  private final @NonNull StatisticsFeatureGate statisticsFeatureGate;

  /**
   * Called once per Matrix message whose resolved sender is a consultant, unless message counting
   * is switched off in the global statistics settings or the organisation (tenant) of the session
   * has not switched statistics on. Best-effort: a failure here must never break the
   * live-notification path it is called alongside, so exceptions are logged and swallowed.
   */
  public void recordMessageSent(String consultantId, Long sessionId) {
    try {
      // Global statistics setting: switching it off stops recording from now on; counts already
      // recorded stay as they are.
      if (!statisticsSettingsService.isMessageCountTrackingEnabled()) {
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
      // Organisation switch: nothing is counted for an organisation that did not switch it on.
      if (!statisticsFeatureGate.isEnabledForTenant(tenantId)) {
        return;
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

  /**
   * Deletes every message-count row of a consultant whose account is being deleted. The rows are
   * only read back for the consultant's own statistics tab, so nothing is left to keep once the
   * account is gone. Aggregated views do not use them.
   *
   * @return number of deleted rows
   */
  public int deleteForConsultant(String consultantId) {
    return consultantMessageStatRepository.deleteByConsultantHmac(
        consultantIdentityHasher.hash(consultantId));
  }
}
