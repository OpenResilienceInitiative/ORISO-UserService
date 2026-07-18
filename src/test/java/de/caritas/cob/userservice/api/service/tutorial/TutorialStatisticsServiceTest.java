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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TutorialStatisticsServiceTest {

  private static final long TENANT_ID = 5L;

  private final Clock clock =
      Clock.fixed(Instant.parse("2026-07-19T10:00:00Z"), ZoneId.of("Europe/Berlin"));

  @Mock private TutorialStatisticsRepository tutorialStatisticsRepository;

  private AuthenticatedUser authenticatedUser;
  private TutorialStatisticsService service;

  @BeforeEach
  void setUp() {
    authenticatedUser = new AuthenticatedUser();
    authenticatedUser.setUserId("admin-user-id");
    authenticatedUser.setUsername("admin");
    authenticatedUser.setAccessToken("token");
    authenticatedUser.setTenantId(TENANT_ID);
    service = new TutorialStatisticsService(tutorialStatisticsRepository, authenticatedUser, clock);
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
                count(TENANT_ID, "frontend", "consultant-walkthrough", 1, "in_progress", 2)));

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
                "frontend", "consultant-walkthrough", 1, "in_progress", 2L));
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
                count(1L, "frontend", "consultant-walkthrough", 1, "completed", 4),
                count(2L, "frontend", "consultant-walkthrough", 1, "skipped", 1),
                count(2L, "admin", "admin-demo-tour", 1, "in_progress", 3)));

    var statistics = service.getStatistics();

    assertThat(statistics.scope()).isEqualTo(TutorialStatisticsScope.PLATFORM);
    assertThat(statistics.tenants()).extracting("tenantId").containsExactly(1L, 2L);
    assertThat(statistics.tenants().get(1).counts()).hasSize(2);
    assertThat(statistics.generatedAt()).isNotBlank();
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
}
