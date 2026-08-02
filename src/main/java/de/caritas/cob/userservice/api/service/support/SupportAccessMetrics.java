package de.caritas.cob.userservice.api.service.support;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;

import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent.OutboxStatus;
import de.caritas.cob.userservice.api.model.SupportAccessSession.SupportAccessSessionStatus;
import de.caritas.cob.userservice.api.port.out.HandshakeOutboxEventRepository;
import de.caritas.cob.userservice.api.port.out.SupportAccessSessionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Operational visibility for support access (ADR-018 §8).
 *
 * <p>The interesting gauges are the ones that expose a security state the API deliberately hides: a
 * withdrawal that has been unproven for too long, and a session past its lease that is not terminal
 * yet. Both mean a support identity may still reach a room it should have lost.
 *
 * <p>Values are refreshed on a schedule rather than read per scrape, so a scraping storm cannot
 * turn into a database load problem.
 */
@Component
@RequiredArgsConstructor
public class SupportAccessMetrics {

  private static final String PREFIX = "oriso.support_access.";

  private final @NonNull MeterRegistry meterRegistry;
  private final @NonNull SupportAccessSessionRepository sessionRepository;
  private final @NonNull HandshakeOutboxEventRepository outboxRepository;

  @Value("${support.metrics.revocation-pending-alert-seconds:120}")
  private long revocationPendingAlertSeconds;

  private final AtomicLong provisioningSessions = new AtomicLong();
  private final AtomicLong activeSessions = new AtomicLong();
  private final AtomicLong revocationPendingSessions = new AtomicLong();
  private final AtomicLong revocationPendingOverdue = new AtomicLong();
  private final AtomicLong provisioningFailedSessions = new AtomicLong();
  private final AtomicLong expiredUnverifiedSessions = new AtomicLong();
  private final AtomicLong outboxPending = new AtomicLong();
  private final AtomicLong outboxFailed = new AtomicLong();
  private final AtomicLong oldestPendingJobAgeSeconds = new AtomicLong();
  private final AtomicLong highestJobAttempts = new AtomicLong();

  @PostConstruct
  void registerGauges() {
    gauge("sessions.provisioning", provisioningSessions);
    gauge("sessions.active", activeSessions);
    gauge("sessions.revocation_pending", revocationPendingSessions);
    gauge("sessions.provisioning_failed", provisioningFailedSessions);
    // Alert: withdrawal claimed but not proven for longer than the agreed window.
    gauge("sessions.revocation_pending_overdue", revocationPendingOverdue);
    // Alert: lease is over but the session is not terminal, so access may still exist.
    gauge("sessions.expired_unverified", expiredUnverifiedSessions);
    gauge("jobs.pending", outboxPending);
    gauge("jobs.failed", outboxFailed);
    gauge("jobs.oldest_pending_age_seconds", oldestPendingJobAgeSeconds);
    gauge("jobs.highest_attempts", highestJobAttempts);
    refresh();
  }

  @Scheduled(fixedDelayString = "${support.metrics.refresh-delay-ms:30000}")
  public void refresh() {
    var now = nowInUtc();

    provisioningSessions.set(
        sessionRepository.countByStatus(SupportAccessSessionStatus.PROVISIONING));
    activeSessions.set(sessionRepository.countByStatus(SupportAccessSessionStatus.ACTIVE));
    revocationPendingSessions.set(
        sessionRepository.countByStatus(SupportAccessSessionStatus.REVOCATION_PENDING));
    provisioningFailedSessions.set(
        sessionRepository.countByStatus(SupportAccessSessionStatus.PROVISIONING_FAILED));
    revocationPendingOverdue.set(
        sessionRepository.countByStatusAndRevocationStartedDateBefore(
            SupportAccessSessionStatus.REVOCATION_PENDING,
            now.minusSeconds(revocationPendingAlertSeconds)));
    expiredUnverifiedSessions.set(
        sessionRepository.countByStatusInAndExpiryDateBefore(
            List.of(
                SupportAccessSessionStatus.PROVISIONING,
                SupportAccessSessionStatus.ACTIVE,
                SupportAccessSessionStatus.REVOCATION_PENDING),
            now));

    outboxPending.set(outboxRepository.countByStatus(OutboxStatus.PENDING));
    outboxFailed.set(outboxRepository.countByStatus(OutboxStatus.FAILED));

    var oldest =
        outboxRepository.findAllByStatusOrderByCreateDateAsc(
            OutboxStatus.PENDING, PageRequest.of(0, 1));
    oldestPendingJobAgeSeconds.set(
        oldest.isEmpty() ? 0L : Duration.between(oldest.get(0).getCreateDate(), now).toSeconds());
    highestJobAttempts.set(
        outboxRepository
            .findAllByStatusOrderByCreateDateAsc(OutboxStatus.PENDING, PageRequest.of(0, 200))
            .stream()
            .mapToLong(event -> event.getAttempts())
            .max()
            .orElse(0L));
  }

  private void gauge(String name, AtomicLong value) {
    meterRegistry.gauge(PREFIX + name, value, AtomicLong::doubleValue);
  }
}
