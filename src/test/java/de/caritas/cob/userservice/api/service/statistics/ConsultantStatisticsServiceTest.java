package de.caritas.cob.userservice.api.service.statistics;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.CONSULTANT_DEFAULT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.SessionAppointment.Kind;
import de.caritas.cob.userservice.api.port.out.ConsultantStatisticsRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantStatisticsRepository.AppointmentKindCount;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsultantStatisticsServiceTest {

  private static final String CONSULTANT_ID = "consultant-user-id";
  private static final Long TENANT_ID = 42L;

  /** Wednesday 15 July 2026, 12:00 in Berlin (10:00 UTC). */
  private static final Instant NOW = Instant.parse("2026-07-15T10:00:00Z");

  @Mock private ConsultantStatisticsRepository consultantStatisticsRepository;
  @Mock private ConsultantMessageStatService consultantMessageStatService;

  private AuthenticatedUser authenticatedUser;
  private ConsultantStatisticsService service;

  @BeforeEach
  void setUp() {
    authenticatedUser = new AuthenticatedUser();
    authenticatedUser.setUserId(CONSULTANT_ID);
    authenticatedUser.setTenantId(TENANT_ID);
    authenticatedUser.setGrantedAuthorities(Set.of(CONSULTANT_DEFAULT));
    service =
        new ConsultantStatisticsService(
            consultantStatisticsRepository,
            consultantMessageStatService,
            authenticatedUser,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  @Test
  void buildStatisticsShouldRejectUsersWithoutConsultantDefaultAuthority() {
    authenticatedUser.setGrantedAuthorities(Set.of("AUTHORIZATION_USER_DEFAULT"));

    assertThatThrownBy(() -> service.buildStatistics("2026-07-01", "2026-07-31"))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(consultantStatisticsRepository);
  }

  @Test
  void buildStatisticsShouldAllowGroupChatAndSupervisorConsultantsCarryingConsultantDefault() {
    // group-chat-consultant / supervisor-consultant never hold the "consultant" role literal but
    // both are granted CONSULTANT_DEFAULT and pass Spring Security's gate on this endpoint.
    authenticatedUser.setGrantedAuthorities(Set.of(CONSULTANT_DEFAULT));

    var statistics = service.buildStatistics("2026-07-01", "2026-07-31");

    assertThat(statistics).isNotNull();
  }

  @Test
  void buildStatisticsShouldRejectMalformedDates() {
    assertThatThrownBy(() -> service.buildStatistics("01.07.2026", "2026-07-31"))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.buildStatistics("2026-07-01", "not-a-date"))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(consultantStatisticsRepository);
  }

  @Test
  void buildStatisticsShouldRejectEndDateBeforeStartDate() {
    assertThatThrownBy(() -> service.buildStatistics("2026-07-31", "2026-07-01"))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(consultantStatisticsRepository);
  }

  @Test
  void buildStatisticsShouldQueryOwnConsultantIdWithEndDateExclusiveUpperBound() {
    var fromDateTime = LocalDateTime.of(2026, 7, 1, 0, 0);
    var toDateTime = LocalDateTime.of(2026, 8, 1, 0, 0);
    when(consultantStatisticsRepository.countAssignedSessionsCreatedInPeriod(
            CONSULTANT_ID, TENANT_ID, fromDateTime, toDateTime))
        .thenReturn(7L);
    when(consultantStatisticsRepository.countActiveSessionsInPeriod(
            CONSULTANT_ID, TENANT_ID, fromDateTime, toDateTime))
        .thenReturn(3L);
    when(consultantMessageStatService.countForConsultant(
            CONSULTANT_ID, TENANT_ID, fromDateTime, toDateTime))
        .thenReturn(5L);

    var statistics = service.buildStatistics("2026-07-01", "2026-07-31");

    assertThat(statistics.startDate()).isEqualTo("2026-07-01");
    assertThat(statistics.endDate()).isEqualTo("2026-07-31");
    assertThat(statistics.numberOfAssignedSessions()).isEqualTo(7L);
    assertThat(statistics.numberOfActiveSessions()).isEqualTo(3L);
    assertThat(statistics.numberOfSentMessages()).isEqualTo(5L);
  }

  @Test
  void buildStatisticsShouldAllowSingleDayPeriod() {
    var fromDateTime = LocalDateTime.of(2026, 7, 11, 0, 0);
    var toDateTime = LocalDateTime.of(2026, 7, 12, 0, 0);
    when(consultantStatisticsRepository.countAssignedSessionsCreatedInPeriod(
            CONSULTANT_ID, TENANT_ID, fromDateTime, toDateTime))
        .thenReturn(1L);
    when(consultantStatisticsRepository.countActiveSessionsInPeriod(
            CONSULTANT_ID, TENANT_ID, fromDateTime, toDateTime))
        .thenReturn(0L);

    var statistics = service.buildStatistics("2026-07-11", "2026-07-11");

    assertThat(statistics.numberOfAssignedSessions()).isEqualTo(1L);
    assertThat(statistics.numberOfActiveSessions()).isZero();
  }

  @Test
  void buildStatisticsShouldPreferTenantContextOverAuthenticatedUserTenantId() {
    TenantContext.setCurrentTenant(7L);
    var fromDateTime = LocalDateTime.of(2026, 7, 1, 0, 0);
    var toDateTime = LocalDateTime.of(2026, 8, 1, 0, 0);

    service.buildStatistics("2026-07-01", "2026-07-31");

    verify(consultantStatisticsRepository)
        .countAssignedSessionsCreatedInPeriod(CONSULTANT_ID, 7L, fromDateTime, toDateTime);
    verify(consultantStatisticsRepository)
        .countActiveSessionsInPeriod(CONSULTANT_ID, 7L, fromDateTime, toDateTime);
  }

  /**
   * OBP #93: a counsellor always sees all of their own numbers. The small-cell thresholds of the
   * global statistics settings apply to aggregated views only, never to the own panel - so a
   * counsellor working alone, with one case and one message, still sees exactly that.
   */
  @Test
  void buildStatisticsShouldNeverSuppressTheCounsellorsOwnNumbers() {
    when(consultantStatisticsRepository.countAssignedSessionsCreatedInPeriod(
            eq(CONSULTANT_ID), eq(TENANT_ID), any(), any()))
        .thenReturn(1L);
    when(consultantStatisticsRepository.countActiveSessionsInPeriod(
            eq(CONSULTANT_ID), eq(TENANT_ID), any(), any()))
        .thenReturn(1L);
    when(consultantMessageStatService.countForConsultant(
            eq(CONSULTANT_ID), eq(TENANT_ID), any(), any()))
        .thenReturn(1L);

    var statistics = service.buildStatistics("2026-07-01", "2026-07-31");

    assertThat(statistics.numberOfAssignedSessions()).isEqualTo(1L);
    assertThat(statistics.numberOfActiveSessions()).isEqualTo(1L);
    assertThat(statistics.numberOfSentMessages()).isEqualTo(1L);
  }

  private static AppointmentKindCount kindCount(Kind kind, long total) {
    return new AppointmentKindCount() {
      @Override
      public Kind getKind() {
        return kind;
      }

      @Override
      public long getTotal() {
        return total;
      }
    };
  }

  /**
   * ORISO-Frontend#2299: realised appointments are the scheduled ones that already started; the
   * period is read in Berlin time, and the upper bound is capped at now.
   */
  @Test
  void buildStatisticsShouldCountRealisedAppointmentsByKindUpToNowInBerlinTime() {
    var julyStartBerlin = Instant.parse("2026-06-30T22:00:00Z");
    when(consultantStatisticsRepository.countScheduledAppointmentsByKindStartingIn(
            CONSULTANT_ID, TENANT_ID, julyStartBerlin, NOW))
        .thenReturn(
            List.of(
                kindCount(Kind.VIDEO, 3L),
                kindCount(Kind.PHONE, 2L),
                kindCount(Kind.ONSITE, 1L),
                kindCount(Kind.CHAT, 4L),
                kindCount(null, 5L)));
    when(consultantStatisticsRepository.countCancelledAppointmentsStartingIn(
            CONSULTANT_ID, TENANT_ID, julyStartBerlin, Instant.parse("2026-07-31T22:00:00Z")))
        .thenReturn(6L);

    var statistics = service.buildStatistics("2026-07-01", "2026-07-31", null);

    assertThat(statistics.numberOfAppointments()).isEqualTo(15L);
    assertThat(statistics.numberOfVideoAppointments()).isEqualTo(3L);
    assertThat(statistics.numberOfPhoneAppointments()).isEqualTo(2L);
    assertThat(statistics.numberOfOnsiteAppointments()).isEqualTo(1L);
    assertThat(statistics.numberOfChatAppointments()).isEqualTo(4L);
    assertThat(statistics.numberOfCancelledAppointments()).isEqualTo(6L);
    assertThat(statistics.breakdown()).isNull();
  }

  @Test
  void buildStatisticsShouldNotCountFuturePeriodsAsRealised() {
    service.buildStatistics("2026-08-01", "2026-08-31", null);

    verify(consultantStatisticsRepository, never())
        .countScheduledAppointmentsByKindStartingIn(any(), any(), any(), any());
  }

  @Test
  void buildStatisticsShouldBreakAYearDownIntoMonths() {
    var statistics = service.buildStatistics("2026-01-01", "2026-12-31", "MONTH");

    assertThat(statistics.breakdown()).hasSize(12);
    assertThat(statistics.breakdown().get(0).startDate()).isEqualTo("2026-01-01");
    assertThat(statistics.breakdown().get(0).endDate()).isEqualTo("2026-01-31");
    assertThat(statistics.breakdown().get(1).endDate()).isEqualTo("2026-02-28");
    assertThat(statistics.breakdown().get(11).startDate()).isEqualTo("2026-12-01");
    assertThat(statistics.breakdown().get(0).breakdown()).isNull();
  }

  @Test
  void buildStatisticsShouldBreakAMonthDownIntoMondayToSundayWeeksClippedToThePeriod() {
    var statistics = service.buildStatistics("2026-07-01", "2026-07-31", "WEEK");

    assertThat(statistics.breakdown())
        .extracting(ConsultantStatisticsService.ConsultantStatistics::startDate)
        .containsExactly("2026-07-01", "2026-07-06", "2026-07-13", "2026-07-20", "2026-07-27");
    assertThat(statistics.breakdown())
        .extracting(ConsultantStatisticsService.ConsultantStatistics::endDate)
        .containsExactly("2026-07-05", "2026-07-12", "2026-07-19", "2026-07-26", "2026-07-31");
  }

  @Test
  void buildStatisticsShouldBreakAWeekDownIntoDays() {
    lenient()
        .when(
            consultantStatisticsRepository.countAssignedSessionsCreatedInPeriod(
                CONSULTANT_ID,
                TENANT_ID,
                LocalDateTime.of(2026, 7, 14, 0, 0),
                LocalDateTime.of(2026, 7, 15, 0, 0)))
        .thenReturn(2L);

    var statistics = service.buildStatistics("2026-07-13", "2026-07-19", "DAY");

    assertThat(statistics.breakdown()).hasSize(7);
    assertThat(statistics.breakdown().get(1).startDate()).isEqualTo("2026-07-14");
    assertThat(statistics.breakdown().get(1).numberOfAssignedSessions()).isEqualTo(2L);
  }

  @Test
  void buildStatisticsShouldRejectUnknownOrTooFineBreakdowns() {
    assertThatThrownBy(() -> service.buildStatistics("2026-07-01", "2026-07-31", "HOUR"))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.buildStatistics("2026-01-01", "2026-12-31", "DAY"))
        .isInstanceOf(BadRequestException.class);
  }
}
