package de.caritas.cob.userservice.api.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminStatisticsRepository;
import de.caritas.cob.userservice.api.port.out.AdminStatisticsRepository.GroupCountProjection;
import de.caritas.cob.userservice.api.port.out.AdminStatisticsRepository.TopicCountProjection;
import de.caritas.cob.userservice.api.service.statistics.AdminDashboardStatisticsService.DashboardScope;
import de.caritas.cob.userservice.api.service.statistics.AdminDashboardStatisticsService.TargetStatistics;
import de.caritas.cob.userservice.api.service.statistics.AdminDashboardStatisticsService.TargetType;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSetting;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSettingsService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminDashboardStatisticsServiceTest {

  private static final long TENANT_ID = 5L;
  private static final long AGENCY_WITH_TEAM = 11L;
  private static final long AGENCY_WITH_FEW_COUNSELORS = 12L;
  private static final String ADMIN_USER_ID = "admin-user-id";

  // Fixed clock: Saturday, 2026-07-11 in Europe/Berlin
  private final Clock clock =
      Clock.fixed(Instant.parse("2026-07-11T10:00:00Z"), ZoneId.of("Europe/Berlin"));

  @Mock private AdminStatisticsRepository adminStatisticsRepository;
  @Mock private AdminAgencyRepository adminAgencyRepository;
  @Mock private StatisticsSettingsService statisticsSettingsService;

  private AuthenticatedUser authenticatedUser;
  private MockEnvironment mockEnvironment;
  private AdminDashboardStatisticsService service;

  @BeforeEach
  void setUp() {
    authenticatedUser = new AuthenticatedUser();
    authenticatedUser.setUserId(ADMIN_USER_ID);
    authenticatedUser.setUsername("admin");
    authenticatedUser.setAccessToken("token");
    authenticatedUser.setTenantId(TENANT_ID);
    mockEnvironment = new MockEnvironment();
    service =
        new AdminDashboardStatisticsService(
            adminStatisticsRepository,
            adminAgencyRepository,
            authenticatedUser,
            clock,
            mockEnvironment,
            statisticsSettingsService);
    lenient().when(statisticsSettingsService.minimumCounsellors(any())).thenReturn(5);
    lenient().when(statisticsSettingsService.chartWeeks()).thenReturn(5);
    lenient().when(statisticsSettingsService.topicMonths()).thenReturn(4);
    TenantContext.clear();
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  private static GroupCountProjection groupCount(Long groupId, long total) {
    return new GroupCountProjection() {
      @Override
      public Long getGroupId() {
        return groupId;
      }

      @Override
      public Long getTotal() {
        return total;
      }
    };
  }

  private static AdminStatisticsRepository.DailyCountProjection dailyCount(
      Long groupId, String day, long total) {
    return new AdminStatisticsRepository.DailyCountProjection() {
      @Override
      public Long getGroupId() {
        return groupId;
      }

      @Override
      public LocalDate getDay() {
        return LocalDate.parse(day);
      }

      @Override
      public Long getTotal() {
        return total;
      }
    };
  }

  private static TopicCountProjection topicCount(Long groupId, Long topicId, long total) {
    return new TopicCountProjection() {
      @Override
      public Long getGroupId() {
        return groupId;
      }

      @Override
      public Long getTopicId() {
        return topicId;
      }

      @Override
      public Long getTotal() {
        return total;
      }
    };
  }

  private void givenEmptyTenantScopedQueries() {
    lenient()
        .when(adminStatisticsRepository.countConsultantsByAgency(TENANT_ID))
        .thenReturn(List.of());
    lenient()
        .when(adminStatisticsRepository.countNewSessionsByAgency(any(), any(), any()))
        .thenReturn(List.of());
    lenient()
        .when(adminStatisticsRepository.countActiveCasesByAgency(TENANT_ID))
        .thenReturn(List.of());
    lenient()
        .when(adminStatisticsRepository.countActiveSessionsSinceByAgency(any(), any()))
        .thenReturn(List.of());
    lenient()
        .when(adminStatisticsRepository.countDailyNewSessionsByAgency(any(), any()))
        .thenReturn(List.of());
    lenient()
        .when(adminStatisticsRepository.countSessionTopicsByAgency(any(), any(), any()))
        .thenReturn(List.of());
    lenient()
        .when(adminStatisticsRepository.countGroupChatsByAgency(any(), any(), any()))
        .thenReturn(List.of());
    lenient().when(adminStatisticsRepository.countConsultantsForTenant(TENANT_ID)).thenReturn(0L);
    lenient()
        .when(adminStatisticsRepository.countGroupChatsForTenant(any(), any(), any()))
        .thenReturn(0L);
  }

  private void givenTenantAdmin() {
    authenticatedUser.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue()));
    TenantContext.setCurrentTenant(TENANT_ID);
  }

  @Test
  void buildDashboard_Should_ThrowForbidden_When_UserHasNoAdminRole() {
    authenticatedUser.setRoles(Set.of(UserRole.CONSULTANT.getValue()));

    assertThatThrownBy(() -> service.buildDashboard()).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void buildDashboard_Should_ThrowForbidden_When_TenantAdminHasNoTenantContext() {
    authenticatedUser.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue()));
    authenticatedUser.setTenantId(null);

    assertThatThrownBy(() -> service.buildDashboard()).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void buildDashboard_Should_ReturnTenantScope_WithTenantTotalAndAgencyRows() {
    givenTenantAdmin();
    givenEmptyTenantScopedQueries();
    when(adminStatisticsRepository.countConsultantsByAgency(TENANT_ID))
        .thenReturn(
            List.of(groupCount(AGENCY_WITH_TEAM, 5), groupCount(AGENCY_WITH_FEW_COUNSELORS, 4)));
    when(adminStatisticsRepository.countActiveCasesByAgency(TENANT_ID))
        .thenReturn(
            List.of(groupCount(AGENCY_WITH_TEAM, 7), groupCount(AGENCY_WITH_FEW_COUNSELORS, 2)));
    when(adminStatisticsRepository.countConsultantsForTenant(TENANT_ID)).thenReturn(9L);

    var dashboard = service.buildDashboard();

    assertThat(dashboard.scope()).isEqualTo(DashboardScope.TENANT);
    assertThat(dashboard.targets()).hasSize(3);

    var tenantRow = dashboard.targets().get(0);
    assertThat(tenantRow.targetType()).isEqualTo(TargetType.TENANT);
    assertThat(tenantRow.tenantId()).isEqualTo(TENANT_ID);
    assertThat(tenantRow.agencyId()).isNull();
    assertThat(tenantRow.counselorCount()).isEqualTo(9);
    assertThat(tenantRow.suppressed()).isFalse();
    // tenant total aggregates over ALL agencies, including individually suppressed ones
    assertThat(tenantRow.metrics().activeCases()).isEqualTo(9);
  }

  @Test
  void buildDashboard_Should_SuppressSmallCellAgencies_When_FewerThanFiveCounselors() {
    givenTenantAdmin();
    givenEmptyTenantScopedQueries();
    when(adminStatisticsRepository.countConsultantsByAgency(TENANT_ID))
        .thenReturn(
            List.of(groupCount(AGENCY_WITH_TEAM, 5), groupCount(AGENCY_WITH_FEW_COUNSELORS, 4)));
    when(adminStatisticsRepository.countActiveCasesByAgency(TENANT_ID))
        .thenReturn(
            List.of(groupCount(AGENCY_WITH_TEAM, 7), groupCount(AGENCY_WITH_FEW_COUNSELORS, 2)));
    when(adminStatisticsRepository.countConsultantsForTenant(TENANT_ID)).thenReturn(9L);

    var dashboard = service.buildDashboard();

    var teamAgency =
        dashboard.targets().stream()
            .filter(target -> Long.valueOf(AGENCY_WITH_TEAM).equals(target.agencyId()))
            .findFirst()
            .orElseThrow();
    assertThat(teamAgency.suppressed()).isFalse();
    assertThat(teamAgency.metrics().activeCases()).isEqualTo(7);

    // 4 counselors is one below the threshold of 5 and must still be suppressed
    var belowThresholdAgency =
        dashboard.targets().stream()
            .filter(target -> Long.valueOf(AGENCY_WITH_FEW_COUNSELORS).equals(target.agencyId()))
            .findFirst()
            .orElseThrow();
    assertThat(belowThresholdAgency.suppressed()).isTrue();
    assertThat(belowThresholdAgency.metrics()).isNull();
    assertThat(belowThresholdAgency.sessionCountsByPeriod()).isNull();
    assertThat(belowThresholdAgency.dailyNewSessions()).isEmpty();
    assertThat(belowThresholdAgency.monthlyTopTopics()).isEmpty();
  }

  @Test
  void buildDashboard_Should_ComputeTopTopicShares_ForTenantTotal() {
    givenTenantAdmin();
    givenEmptyTenantScopedQueries();
    when(adminStatisticsRepository.countConsultantsByAgency(TENANT_ID))
        .thenReturn(List.of(groupCount(AGENCY_WITH_TEAM, 5)));
    when(adminStatisticsRepository.countConsultantsForTenant(TENANT_ID)).thenReturn(5L);
    // current month: topic 100 has 3 sessions, topic 200 has 1 → top topic 100 with 75%
    when(adminStatisticsRepository.countSessionTopicsByAgency(
            any(), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenAnswer(
            invocation -> {
              LocalDateTime from = invocation.getArgument(1);
              if (from.getMonthValue() == 7) {
                return List.of(
                    topicCount(AGENCY_WITH_TEAM, 100L, 3), topicCount(AGENCY_WITH_TEAM, 200L, 1));
              }
              return List.of();
            });

    var dashboard = service.buildDashboard();

    var tenantRow = dashboard.targets().get(0);
    assertThat(tenantRow.monthlyTopTopics()).hasSize(1);
    var topTopic = tenantRow.monthlyTopTopics().get(0);
    assertThat(topTopic.month()).isEqualTo("2026-07");
    assertThat(topTopic.topicId()).isEqualTo(100L);
    assertThat(topTopic.sessionCount()).isEqualTo(3);
    assertThat(topTopic.sharePercent()).isEqualTo(75);
  }

  @Test
  void buildDashboard_Should_MapDailySessionCounts_ToSortedSeries() {
    givenTenantAdmin();
    givenEmptyTenantScopedQueries();
    when(adminStatisticsRepository.countConsultantsByAgency(TENANT_ID))
        .thenReturn(List.of(groupCount(AGENCY_WITH_TEAM, 5)));
    when(adminStatisticsRepository.countConsultantsForTenant(TENANT_ID)).thenReturn(5L);
    when(adminStatisticsRepository.countDailyNewSessionsByAgency(any(), any()))
        .thenReturn(
            List.of(
                dailyCount(AGENCY_WITH_TEAM, "2026-07-10", 2),
                dailyCount(AGENCY_WITH_TEAM, "2026-07-08", 1)));

    var dashboard = service.buildDashboard();

    var agencyRow =
        dashboard.targets().stream()
            .filter(target -> Long.valueOf(AGENCY_WITH_TEAM).equals(target.agencyId()))
            .findFirst()
            .orElseThrow();
    assertThat(agencyRow.dailyNewSessions())
        .extracting(AdminDashboardStatisticsService.DailyCount::date)
        .containsExactly("2026-07-08", "2026-07-10");
  }

  @Test
  void buildDashboard_Should_ReturnOnlyAssignedAgencies_ForRestrictedAgencyAdmin() {
    authenticatedUser.setRoles(Set.of(UserRole.RESTRICTED_AGENCY_ADMIN.getValue()));
    TenantContext.setCurrentTenant(TENANT_ID);
    givenEmptyTenantScopedQueries();
    when(adminStatisticsRepository.countConsultantsByAgency(TENANT_ID))
        .thenReturn(List.of(groupCount(AGENCY_WITH_TEAM, 2), groupCount(99L, 5)));

    var assignedAgency = new AdminAgency();
    assignedAgency.setAgencyId(AGENCY_WITH_TEAM);
    when(adminAgencyRepository.findByAdminId(anyString())).thenReturn(List.of(assignedAgency));

    var dashboard = service.buildDashboard();

    assertThat(dashboard.scope()).isEqualTo(DashboardScope.AGENCY);
    assertThat(dashboard.targets())
        .extracting(AdminDashboardStatisticsService.TargetStatistics::agencyId)
        .containsExactly(AGENCY_WITH_TEAM);
  }

  @Test
  void buildDashboard_Should_SkipSuppression_When_DisabledOutsideProduction() {
    givenTenantAdmin();
    givenEmptyTenantScopedQueries();
    when(adminStatisticsRepository.countConsultantsByAgency(TENANT_ID))
        .thenReturn(List.of(groupCount(AGENCY_WITH_FEW_COUNSELORS, 1)));
    when(adminStatisticsRepository.countActiveCasesByAgency(TENANT_ID))
        .thenReturn(List.of(groupCount(AGENCY_WITH_FEW_COUNSELORS, 2)));
    when(adminStatisticsRepository.countConsultantsForTenant(TENANT_ID)).thenReturn(1L);
    ReflectionTestUtils.setField(service, "smallCellSuppressionEnabled", false);

    var dashboard = service.buildDashboard();

    assertThat(dashboard.suppressionDisabled()).isTrue();
    var singleCounselorAgency =
        dashboard.targets().stream()
            .filter(target -> Long.valueOf(AGENCY_WITH_FEW_COUNSELORS).equals(target.agencyId()))
            .findFirst()
            .orElseThrow();
    assertThat(singleCounselorAgency.suppressed()).isFalse();
    assertThat(singleCounselorAgency.metrics()).isNotNull();
    assertThat(singleCounselorAgency.metrics().activeCases()).isEqualTo(2);
  }

  @Test
  void buildDashboard_Should_EnforceSuppression_When_DisabledButProdProfileActive() {
    givenTenantAdmin();
    givenEmptyTenantScopedQueries();
    when(adminStatisticsRepository.countConsultantsByAgency(TENANT_ID))
        .thenReturn(List.of(groupCount(AGENCY_WITH_FEW_COUNSELORS, 1)));
    when(adminStatisticsRepository.countActiveCasesByAgency(TENANT_ID))
        .thenReturn(List.of(groupCount(AGENCY_WITH_FEW_COUNSELORS, 2)));
    when(adminStatisticsRepository.countConsultantsForTenant(TENANT_ID)).thenReturn(1L);
    ReflectionTestUtils.setField(service, "smallCellSuppressionEnabled", false);
    mockEnvironment.setActiveProfiles("prod");

    var dashboard = service.buildDashboard();

    // fail-closed: prod ignores the disable switch
    assertThat(dashboard.suppressionDisabled()).isFalse();
    var singleCounselorAgency =
        dashboard.targets().stream()
            .filter(target -> Long.valueOf(AGENCY_WITH_FEW_COUNSELORS).equals(target.agencyId()))
            .findFirst()
            .orElseThrow();
    assertThat(singleCounselorAgency.suppressed()).isTrue();
    assertThat(singleCounselorAgency.metrics()).isNull();
  }

  @Test
  void buildDashboard_Should_ReturnPlatformScope_ForPlatformAdmin() {
    authenticatedUser.setRoles(
        Set.of(UserRole.TENANT_ADMIN.getValue(), UserRole.AGENCY_ADMIN.getValue()));
    authenticatedUser.setTenantId(0L);
    TenantContext.setCurrentTenant(0L);

    lenient()
        .when(adminStatisticsRepository.countConsultantsByTenant())
        .thenReturn(List.of(groupCount(1L, 5), groupCount(2L, 1)));
    lenient()
        .when(adminStatisticsRepository.countNewSessionsByTenant(any(), any()))
        .thenReturn(List.of());
    lenient()
        .when(adminStatisticsRepository.countActiveCasesByTenant())
        .thenReturn(List.of(groupCount(1L, 10), groupCount(2L, 3)));
    lenient()
        .when(adminStatisticsRepository.countActiveSessionsSinceByTenant(any()))
        .thenReturn(List.of());
    lenient()
        .when(adminStatisticsRepository.countDailyNewSessionsByTenant(any()))
        .thenReturn(List.of());
    lenient()
        .when(adminStatisticsRepository.countSessionTopicsByTenant(any(), any()))
        .thenReturn(List.of());
    lenient()
        .when(adminStatisticsRepository.countGroupChatsByTenant(any(), any()))
        .thenReturn(List.of());

    var dashboard = service.buildDashboard();

    assertThat(dashboard.scope()).isEqualTo(DashboardScope.PLATFORM);
    assertThat(dashboard.targets()).hasSize(3);

    var tenantOne =
        dashboard.targets().stream()
            .filter(target -> Long.valueOf(1L).equals(target.tenantId()))
            .findFirst()
            .orElseThrow();
    assertThat(tenantOne.targetType()).isEqualTo(TargetType.TENANT);
    assertThat(tenantOne.suppressed()).isFalse();
    assertThat(tenantOne.metrics().activeCases()).isEqualTo(10);

    // tenant 2 has a single counselor and must be suppressed platform-wide as well
    var tenantTwo =
        dashboard.targets().stream()
            .filter(target -> Long.valueOf(2L).equals(target.tenantId()))
            .findFirst()
            .orElseThrow();
    assertThat(tenantTwo.suppressed()).isTrue();
    assertThat(tenantTwo.metrics()).isNull();
  }

  private void givenPlatformAdminWithTenants(List<GroupCountProjection> counsellorsByTenant) {
    authenticatedUser.setRoles(
        Set.of(UserRole.TENANT_ADMIN.getValue(), UserRole.AGENCY_ADMIN.getValue()));
    authenticatedUser.setTenantId(0L);
    TenantContext.setCurrentTenant(0L);
    lenient()
        .when(adminStatisticsRepository.countConsultantsByTenant())
        .thenReturn(counsellorsByTenant);
    lenient()
        .when(adminStatisticsRepository.countNewSessionsByTenant(any(), any()))
        .thenReturn(List.of());
    lenient()
        .when(adminStatisticsRepository.countActiveCasesByTenant())
        .thenReturn(List.of(groupCount(1L, 10), groupCount(2L, 3)));
    lenient()
        .when(adminStatisticsRepository.countActiveSessionsSinceByTenant(any()))
        .thenReturn(List.of());
    lenient()
        .when(adminStatisticsRepository.countDailyNewSessionsByTenant(any()))
        .thenReturn(List.of());
    lenient()
        .when(adminStatisticsRepository.countSessionTopicsByTenant(any(), any()))
        .thenReturn(List.of());
    lenient()
        .when(adminStatisticsRepository.countGroupChatsByTenant(any(), any()))
        .thenReturn(List.of(groupCount(1L, 2), groupCount(2L, 1)));
  }

  @Test
  void buildDashboard_Should_ShowPlatformTotal_When_EachTenantIsBelowThresholdButPlatformIsNot() {
    // OBP #93: 3 + 3 counsellors - every Träger is below 5, the platform with 6 is not
    givenPlatformAdminWithTenants(List.of(groupCount(1L, 3), groupCount(2L, 3)));

    var dashboard = service.buildDashboard();

    var platform =
        dashboard.targets().stream()
            .filter(target -> target.targetType() == TargetType.PLATFORM)
            .findFirst()
            .orElseThrow();
    assertThat(platform.tenantId()).isNull();
    assertThat(platform.agencyId()).isNull();
    assertThat(platform.counselorCount()).isEqualTo(6);
    assertThat(platform.suppressed()).isFalse();
    assertThat(platform.metrics().activeCases()).isEqualTo(13);
    assertThat(platform.groupChatCountsByPeriod().total()).isEqualTo(3);
    // the single Träger rows stay protected
    assertThat(
            dashboard.targets().stream()
                .filter(target -> target.targetType() == TargetType.TENANT)
                .allMatch(TargetStatistics::suppressed))
        .isTrue();
  }

  @Test
  void buildDashboard_Should_SuppressPlatformTotal_When_BelowThePlatformThreshold() {
    givenPlatformAdminWithTenants(List.of(groupCount(1L, 3), groupCount(2L, 3)));
    when(statisticsSettingsService.minimumCounsellors(
            StatisticsSetting.PLATFORM_MINIMUM_COUNSELLORS))
        .thenReturn(7);

    var dashboard = service.buildDashboard();

    var platform = dashboard.targets().get(0);
    assertThat(platform.targetType()).isEqualTo(TargetType.PLATFORM);
    assertThat(platform.suppressed()).isTrue();
    assertThat(platform.metrics()).isNull();
  }

  @Test
  void buildDashboard_Should_UseTheConfiguredAgencyThreshold() {
    givenTenantAdmin();
    givenEmptyTenantScopedQueries();
    when(statisticsSettingsService.minimumCounsellors(StatisticsSetting.AGENCY_MINIMUM_COUNSELLORS))
        .thenReturn(2);
    when(adminStatisticsRepository.countConsultantsByAgency(TENANT_ID))
        .thenReturn(List.of(groupCount(AGENCY_WITH_FEW_COUNSELORS, 2)));
    when(adminStatisticsRepository.countActiveCasesByAgency(TENANT_ID))
        .thenReturn(List.of(groupCount(AGENCY_WITH_FEW_COUNSELORS, 4)));
    when(adminStatisticsRepository.countConsultantsForTenant(TENANT_ID)).thenReturn(2L);

    var dashboard = service.buildDashboard();

    var agency =
        dashboard.targets().stream()
            .filter(target -> target.targetType() == TargetType.AGENCY)
            .findFirst()
            .orElseThrow();
    assertThat(agency.suppressed()).isFalse();
    assertThat(agency.metrics().activeCases()).isEqualTo(4);
    // the tenant total still uses the tenant threshold (default 5)
    assertThat(dashboard.targets().get(0).targetType()).isEqualTo(TargetType.TENANT);
    assertThat(dashboard.targets().get(0).suppressed()).isTrue();
  }

  @Test
  void buildDashboard_Should_UseTheConfiguredTenantThreshold() {
    givenTenantAdmin();
    givenEmptyTenantScopedQueries();
    when(statisticsSettingsService.minimumCounsellors(StatisticsSetting.TENANT_MINIMUM_COUNSELLORS))
        .thenReturn(3);
    when(adminStatisticsRepository.countConsultantsForTenant(TENANT_ID)).thenReturn(3L);

    var dashboard = service.buildDashboard();

    assertThat(dashboard.targets().get(0).targetType()).isEqualTo(TargetType.TENANT);
    assertThat(dashboard.targets().get(0).suppressed()).isFalse();
  }

  @Test
  void buildDashboard_Should_QueryTodaysTimeWindows_ByDefault() {
    givenTenantAdmin();
    givenEmptyTenantScopedQueries();

    service.buildDashboard();

    // Saturday 2026-07-11: this week starts Monday 2026-07-06, plus four weeks before
    verify(adminStatisticsRepository)
        .countDailyNewSessionsByAgency(TENANT_ID, LocalDateTime.of(2026, 6, 8, 0, 0));
    assertThat(topicMonthsQueried()).containsExactly("2026-07", "2026-06", "2026-05", "2026-04");
  }

  @Test
  void buildDashboard_Should_UseTheConfiguredChartWeeks() {
    givenTenantAdmin();
    givenEmptyTenantScopedQueries();
    when(statisticsSettingsService.chartWeeks()).thenReturn(12);

    service.buildDashboard();

    // this week plus eleven before
    verify(adminStatisticsRepository)
        .countDailyNewSessionsByAgency(TENANT_ID, LocalDateTime.of(2026, 4, 20, 0, 0));
  }

  @Test
  void buildDashboard_Should_UseTheConfiguredTopicMonths() {
    givenTenantAdmin();
    givenEmptyTenantScopedQueries();
    when(statisticsSettingsService.topicMonths()).thenReturn(12);
    when(adminStatisticsRepository.countConsultantsByAgency(TENANT_ID))
        .thenReturn(List.of(groupCount(AGENCY_WITH_TEAM, 5)));
    when(adminStatisticsRepository.countConsultantsForTenant(TENANT_ID)).thenReturn(5L);
    when(adminStatisticsRepository.countSessionTopicsByAgency(
            any(), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenAnswer(
            invocation -> {
              LocalDateTime from = invocation.getArgument(1);
              return from.toLocalDate().equals(LocalDate.of(2025, 8, 1))
                  ? List.of(topicCount(AGENCY_WITH_TEAM, 300L, 2))
                  : List.of();
            });

    var dashboard = service.buildDashboard();

    assertThat(topicMonthsQueried())
        .containsExactly(
            "2026-07", "2026-06", "2026-05", "2026-04", "2026-03", "2026-02", "2026-01", "2025-12",
            "2025-11", "2025-10", "2025-09", "2025-08");
    assertThat(dashboard.targets().get(0).monthlyTopTopics())
        .extracting(AdminDashboardStatisticsService.MonthlyTopic::month)
        .containsExactly("2025-08");
  }

  @Test
  void buildDashboard_Should_TellTheDashboardWhichWindowsItCovers() {
    givenTenantAdmin();
    givenEmptyTenantScopedQueries();
    when(statisticsSettingsService.chartWeeks()).thenReturn(26);
    when(statisticsSettingsService.topicMonths()).thenReturn(6);

    var dashboard = service.buildDashboard();

    assertThat(dashboard.chartWeeks()).isEqualTo(26);
    assertThat(dashboard.topicMonths()).isEqualTo(6);
  }

  private List<String> topicMonthsQueried() {
    var from = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(adminStatisticsRepository, atLeast(0))
        .countSessionTopicsByAgency(any(), from.capture(), any(LocalDateTime.class));
    return from.getAllValues().stream().map(value -> YearMonth.from(value).toString()).toList();
  }
}
