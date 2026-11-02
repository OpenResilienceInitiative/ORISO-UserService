package de.caritas.cob.userservice.api.service.tutorial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.TutorialStatisticsRepository;
import de.caritas.cob.userservice.api.port.out.TutorialStatisticsRepository.TutorialCountProjection;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSetting;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSettingsService;
import de.caritas.cob.userservice.api.service.tutorial.TutorialStatisticsService.TutorialStatisticsScope;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TutorialStatisticsServiceTest {

  private static final long TENANT_ID = 5L;
  private static final int MINIMUM = 5;

  private final Clock clock =
      Clock.fixed(Instant.parse("2026-07-19T10:00:00Z"), ZoneId.of("Europe/Berlin"));

  @Mock private TutorialStatisticsRepository tutorialStatisticsRepository;
  @Mock private StatisticsSettingsService statisticsSettingsService;

  private MockEnvironment environment;

  private AuthenticatedUser authenticatedUser;
  private TutorialStatisticsService service;

  @BeforeEach
  void setUp() {
    authenticatedUser = new AuthenticatedUser();
    authenticatedUser.setUserId("admin-user-id");
    authenticatedUser.setUsername("admin");
    authenticatedUser.setAccessToken("token");
    authenticatedUser.setTenantId(TENANT_ID);
    environment = new MockEnvironment();
    when(statisticsSettingsService.minimumCounsellors(StatisticsSetting.TENANT_MINIMUM_COUNSELLORS))
        .thenReturn(MINIMUM);
    service =
        new TutorialStatisticsService(
            tutorialStatisticsRepository,
            authenticatedUser,
            clock,
            statisticsSettingsService,
            environment);
    TenantContext.clear();
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  private static TutorialCountProjection count(
      Long tenantId, String surface, String tourId, int tourVersion, String status, long total) {
    return new TutorialCountProjection() {
      @Override
      public Long getTenantId() {
        return tenantId;
      }

      @Override
      public String getSurface() {
        return surface;
      }

      @Override
      public String getTourId() {
        return tourId;
      }

      @Override
      public Integer getTourVersion() {
        return tourVersion;
      }

      @Override
      public String getStatus() {
        return status;
      }

      @Override
      public Long getTotal() {
        return total;
      }
    };
  }

  @Test
  void tenantSuperAdmin_receivesOnlyOwnTenantCounts() {
    authenticatedUser.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue()));
    when(tutorialStatisticsRepository.countByTenant(TENANT_ID))
        .thenReturn(
            List.of(
                count(TENANT_ID, "frontend", "consultant-walkthrough", 1, "completed", 7),
                count(TENANT_ID, "frontend", "consultant-walkthrough", 1, "in_progress", 3)));

    var statistics = service.getStatistics();

    assertThat(statistics.scope()).isEqualTo(TutorialStatisticsScope.TENANT);
    assertThat(statistics.tenants()).hasSize(1);
    var tenant = statistics.tenants().get(0);
    assertThat(tenant.tenantId()).isEqualTo(TENANT_ID);
    assertThat(tenant.counts())
        .extracting("surface", "tourId", "tourVersion", "status", "total")
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(
                "frontend", "consultant-walkthrough", 1, "completed", 7L),
            org.assertj.core.groups.Tuple.tuple(
                "frontend", "consultant-walkthrough", 1, "in_progress", 3L));
    verify(tutorialStatisticsRepository).countByTenant(TENANT_ID);
  }

  @Test
  void singleTenantAdmin_receivesOnlyOwnTenantCounts() {
    authenticatedUser.setRoles(Set.of(UserRole.SINGLE_TENANT_ADMIN.getValue()));
    when(tutorialStatisticsRepository.countByTenant(TENANT_ID)).thenReturn(List.of());

    var statistics = service.getStatistics();

    assertThat(statistics.scope()).isEqualTo(TutorialStatisticsScope.TENANT);
    verify(tutorialStatisticsRepository).countByTenant(TENANT_ID);
  }

  @Test
  void tenantAdmin_prefersTenantContextOverTokenTenant() {
    authenticatedUser.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue()));
    TenantContext.setCurrentTenant(9L);
    when(tutorialStatisticsRepository.countByTenant(9L)).thenReturn(List.of());

    service.getStatistics();

    verify(tutorialStatisticsRepository).countByTenant(9L);
  }

  @Test
  void platformAdmin_receivesGlobalCountsGroupedByTenant() {
    authenticatedUser.setTenantId(0L);
    authenticatedUser.setRoles(
        Set.of(UserRole.TENANT_ADMIN.getValue(), UserRole.AGENCY_ADMIN.getValue()));
    when(tutorialStatisticsRepository.countGlobal())
        .thenReturn(
            List.of(
                count(1L, "frontend", "consultant-walkthrough", 1, "completed", 6),
                count(2L, "frontend", "consultant-walkthrough", 1, "skipped", 5),
                count(2L, "admin", "admin-demo-tour", 1, "in_progress", 7)));

    var statistics = service.getStatistics();

    assertThat(statistics.scope()).isEqualTo(TutorialStatisticsScope.PLATFORM);
    assertThat(statistics.tenants()).extracting("tenantId").containsExactly(1L, 2L);
    assertThat(statistics.tenants().get(1).counts()).hasSize(2);
    assertThat(statistics.generatedAt()).isNotBlank();
  }

  @Test
  void tenantAdmin_withoutResolvableTenant_isForbidden() {
    authenticatedUser.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue()));
    authenticatedUser.setTenantId(null);

    assertThatThrownBy(() -> service.getStatistics()).isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(tutorialStatisticsRepository);
  }

  @Test
  void agencyAdmin_isForbidden() {
    authenticatedUser.setRoles(Set.of(UserRole.RESTRICTED_AGENCY_ADMIN.getValue()));

    assertThatThrownBy(() -> service.getStatistics()).isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(tutorialStatisticsRepository);
  }

  @Test
  void agencySuperAdmin_withoutTenantAdminRole_isForbidden() {
    authenticatedUser.setRoles(Set.of(UserRole.AGENCY_ADMIN.getValue()));

    assertThatThrownBy(() -> service.getStatistics()).isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(tutorialStatisticsRepository);
  }

  @Test
  void consultant_isForbidden() {
    authenticatedUser.setRoles(Set.of(UserRole.CONSULTANT.getValue()));

    assertThatThrownBy(() -> service.getStatistics()).isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(tutorialStatisticsRepository);
  }

  // ---- small-cell suppression (UserService#527, option A) ----

  private void asPlatformAdmin() {
    authenticatedUser.setTenantId(0L);
    authenticatedUser.setRoles(
        Set.of(UserRole.TENANT_ADMIN.getValue(), UserRole.AGENCY_ADMIN.getValue()));
  }

  @Test
  void groupBelowMinimum_isOneSuppressedRowWithoutAnyCountOrStatus() {
    authenticatedUser.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue()));
    when(tutorialStatisticsRepository.countByTenant(TENANT_ID))
        .thenReturn(
            List.of(count(TENANT_ID, "frontend", "consultant-walkthrough", 1, "completed", 1)));

    var counts = service.getStatistics().tenants().get(0).counts();

    assertThat(counts).hasSize(1);
    var row = counts.get(0);
    assertThat(row.suppressed()).isTrue();
    assertThat(row.total()).isZero();
    assertThat(row.status()).isNull();
    assertThat(row.surface()).isEqualTo("frontend");
    assertThat(row.tourId()).isEqualTo("consultant-walkthrough");
    assertThat(row.tourVersion()).isEqualTo(1);
  }

  @Test
  void contributorsAreCountedOverAllStatusesOfTheGroup() {
    authenticatedUser.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue()));
    // 2 + 2 + 1 = 5 users: exactly the minimum, so the group is shown with its real split
    when(tutorialStatisticsRepository.countByTenant(TENANT_ID))
        .thenReturn(
            List.of(
                count(TENANT_ID, "frontend", "t", 1, "completed", 2),
                count(TENANT_ID, "frontend", "t", 1, "skipped", 2),
                count(TENANT_ID, "frontend", "t", 1, "in_progress", 1)));

    var counts = service.getStatistics().tenants().get(0).counts();

    assertThat(counts).hasSize(3).allMatch(row -> !row.suppressed());
    assertThat(counts).extracting("total").containsExactlyInAnyOrder(2L, 2L, 1L);
  }

  @Test
  void groupOneBelowMinimum_isSuppressedEvenWhenNoSingleStatusIsLarge() {
    authenticatedUser.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue()));
    when(tutorialStatisticsRepository.countByTenant(TENANT_ID))
        .thenReturn(
            List.of(
                count(TENANT_ID, "frontend", "t", 1, "completed", 2),
                count(TENANT_ID, "frontend", "t", 1, "skipped", 2)));

    var counts = service.getStatistics().tenants().get(0).counts();

    assertThat(counts).hasSize(1);
    assertThat(counts.get(0).suppressed()).isTrue();
  }

  @Test
  void suppressedVersionNextToShownVersion_cannotBeDerivedFromTheResponse() {
    authenticatedUser.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue()));
    // v1 has 3 users (hidden), v2 has 9 (shown). The response has no total across versions and
    // the hidden row carries no number, so v1 cannot be read or computed from it.
    when(tutorialStatisticsRepository.countByTenant(TENANT_ID))
        .thenReturn(
            List.of(
                count(TENANT_ID, "frontend", "t", 1, "completed", 2),
                count(TENANT_ID, "frontend", "t", 1, "skipped", 1),
                count(TENANT_ID, "frontend", "t", 2, "completed", 9)));

    var counts = service.getStatistics().tenants().get(0).counts();

    var hidden = counts.stream().filter(row -> row.tourVersion() == 1).toList();
    var shown = counts.stream().filter(row -> row.tourVersion() == 2).toList();
    assertThat(hidden).hasSize(1);
    assertThat(hidden.get(0).suppressed()).isTrue();
    assertThat(hidden.get(0).total()).isZero();
    assertThat(shown).extracting("total").containsExactly(9L);
    assertThat(shown.get(0).suppressed()).isFalse();
  }

  @Test
  void platformScope_suppressesPerTenantGroupAndAddsNoTotalAcrossTenants() {
    asPlatformAdmin();
    when(tutorialStatisticsRepository.countGlobal())
        .thenReturn(
            List.of(
                count(1L, "frontend", "t", 1, "completed", 8),
                count(2L, "frontend", "t", 1, "completed", 2)));

    var statistics = service.getStatistics();

    assertThat(statistics.tenants().get(0).counts().get(0).total()).isEqualTo(8L);
    assertThat(statistics.tenants().get(1).counts())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.suppressed()).isTrue();
              assertThat(row.total()).isZero();
            });
  }

  @Test
  void usesTheConfiguredTenantMinimum() {
    authenticatedUser.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue()));
    when(statisticsSettingsService.minimumCounsellors(StatisticsSetting.TENANT_MINIMUM_COUNSELLORS))
        .thenReturn(2);
    when(tutorialStatisticsRepository.countByTenant(TENANT_ID))
        .thenReturn(
            List.of(
                count(TENANT_ID, "frontend", "a", 1, "completed", 2),
                count(TENANT_ID, "frontend", "b", 1, "completed", 1)));

    var statistics = service.getStatistics();

    assertThat(statistics.smallCellMinimum()).isEqualTo(2);
    assertThat(statistics.tenants().get(0).counts())
        .extracting("tourId", "suppressed")
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("a", false),
            org.assertj.core.groups.Tuple.tuple("b", true));
  }

  @Test
  void switchOff_isHonouredOnlyOnLocalAndTestingProfiles() {
    authenticatedUser.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue()));
    ReflectionTestUtils.setField(service, "smallCellSuppressionEnabled", false);
    when(tutorialStatisticsRepository.countByTenant(TENANT_ID))
        .thenReturn(List.of(count(TENANT_ID, "frontend", "t", 1, "completed", 1)));

    environment.setActiveProfiles("testing");
    var open = service.getStatistics();
    assertThat(open.tenants().get(0).counts().get(0).suppressed()).isFalse();
    assertThat(open.tenants().get(0).counts().get(0).total()).isEqualTo(1L);
    assertThat(open.smallCellMinimum()).isZero();

    for (var profile : List.of("prod", "dev", "staging")) {
      environment.setActiveProfiles(profile);
      assertThat(service.getStatistics().tenants().get(0).counts().get(0).suppressed())
          .as("profile %s", profile)
          .isTrue();
    }
    environment.setActiveProfiles();
    assertThat(service.getStatistics().tenants().get(0).counts().get(0).suppressed()).isTrue();
  }
}
