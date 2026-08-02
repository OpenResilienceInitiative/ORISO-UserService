package de.caritas.cob.userservice.api.service.support;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent;
import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent.OutboxStatus;
import de.caritas.cob.userservice.api.model.SupportAccessSession.SupportAccessSessionStatus;
import de.caritas.cob.userservice.api.port.out.HandshakeOutboxEventRepository;
import de.caritas.cob.userservice.api.port.out.SupportAccessSessionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SupportAccessMetricsTest {

  private SimpleMeterRegistry meterRegistry;
  private SupportAccessMetrics metrics;

  @Mock private SupportAccessSessionRepository sessionRepository;
  @Mock private HandshakeOutboxEventRepository outboxRepository;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    metrics = new SupportAccessMetrics(meterRegistry, sessionRepository, outboxRepository);
    ReflectionTestUtils.setField(metrics, "revocationPendingAlertSeconds", 120L);
    lenient().when(sessionRepository.countByStatus(any())).thenReturn(0L);
    lenient()
        .when(sessionRepository.countByStatusAndRevocationStartedDateBefore(any(), any()))
        .thenReturn(0L);
    lenient()
        .when(sessionRepository.countByStatusInAndExpiryDateBefore(any(), any()))
        .thenReturn(0L);
    lenient().when(outboxRepository.countByStatus(any())).thenReturn(0L);
    lenient()
        .when(outboxRepository.findAllByStatusOrderByCreateDateAsc(any(), any()))
        .thenReturn(List.of());
  }

  @Test
  void refresh_Should_ExposeTheWithdrawalAlertGauges() {
    when(sessionRepository.countByStatus(SupportAccessSessionStatus.ACTIVE)).thenReturn(3L);
    when(sessionRepository.countByStatus(SupportAccessSessionStatus.REVOCATION_PENDING))
        .thenReturn(2L);
    when(sessionRepository.countByStatusAndRevocationStartedDateBefore(
            eq(SupportAccessSessionStatus.REVOCATION_PENDING), any()))
        .thenReturn(1L);
    when(sessionRepository.countByStatusInAndExpiryDateBefore(any(), any())).thenReturn(4L);

    metrics.registerGauges();

    assertThat(gauge("oriso.support_access.sessions.active")).isEqualTo(3.0);
    assertThat(gauge("oriso.support_access.sessions.revocation_pending")).isEqualTo(2.0);
    // The two gauges an operator is actually paged on: a withdrawal claimed but unproven, and a
    // lease that ran out while the session is still non-terminal.
    assertThat(gauge("oriso.support_access.sessions.revocation_pending_overdue")).isEqualTo(1.0);
    assertThat(gauge("oriso.support_access.sessions.expired_unverified")).isEqualTo(4.0);
  }

  @Test
  void refresh_Should_ReportTheOldestPendingJobAndTheHighestAttemptCount() {
    var old = nowInUtc().minusMinutes(5);
    when(outboxRepository.countByStatus(OutboxStatus.PENDING)).thenReturn(2L);
    when(outboxRepository.findAllByStatusOrderByCreateDateAsc(eq(OutboxStatus.PENDING), any()))
        .thenReturn(
            List.of(
                HandshakeOutboxEvent.builder().createDate(old).attempts(3).build(),
                HandshakeOutboxEvent.builder().createDate(nowInUtc()).attempts(7).build()));

    metrics.registerGauges();

    assertThat(gauge("oriso.support_access.jobs.pending")).isEqualTo(2.0);
    assertThat(gauge("oriso.support_access.jobs.oldest_pending_age_seconds"))
        .isGreaterThanOrEqualTo(290.0);
    assertThat(gauge("oriso.support_access.jobs.highest_attempts")).isEqualTo(7.0);
  }

  @Test
  void refresh_Should_ReportZeroWhenThereIsNoWork() {
    metrics.registerGauges();

    assertThat(gauge("oriso.support_access.jobs.oldest_pending_age_seconds")).isZero();
    assertThat(gauge("oriso.support_access.jobs.highest_attempts")).isZero();
  }

  private double gauge(String name) {
    return meterRegistry.get(name).gauge().value();
  }
}
