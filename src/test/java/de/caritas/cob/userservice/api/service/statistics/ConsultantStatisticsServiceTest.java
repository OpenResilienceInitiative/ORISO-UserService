package de.caritas.cob.userservice.api.service.statistics;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.CONSULTANT_DEFAULT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.ConsultantStatisticsRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
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
            consultantStatisticsRepository, consultantMessageStatService, authenticatedUser);
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
}
