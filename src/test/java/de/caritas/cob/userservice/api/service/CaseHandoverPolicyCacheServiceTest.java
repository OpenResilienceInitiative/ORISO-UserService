package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.TenantCaseHandoverPolicyCache;
import de.caritas.cob.userservice.api.port.out.TenantCaseHandoverPolicyCacheRepository;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.tenantadminservice.generated.web.TenantControllerApi;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverPolicies;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantPermissionPolicies;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class CaseHandoverPolicyCacheServiceTest {

  @Mock private TenantCaseHandoverPolicyCacheRepository repository;
  @Mock private TenantAdminServiceApiControllerFactory tenantServiceFactory;
  @Mock private TenantControllerApi tenantControllerApi;
  @Mock private ScheduledTaskClaimService scheduledTaskClaimService;
  private final Clock clock = Clock.fixed(Instant.parse("2026-08-16T10:00:00Z"), ZoneOffset.UTC);
  private CaseHandoverPolicyCacheService service;

  @BeforeEach
  void setUp() {
    service =
        new CaseHandoverPolicyCacheService(
            repository, tenantServiceFactory, scheduledTaskClaimService, clock);
    lenient().when(tenantServiceFactory.createControllerApi()).thenReturn(tenantControllerApi);
    lenient().when(scheduledTaskClaimService.tryClaim(anyString(), any())).thenReturn(true);
  }

  @Test
  void refresh_persistsOnlyTheRequestedTenantSnapshot() {
    var policies = new CaseHandoverPolicies().reasons(Map.of());
    when(repository.findById(42L)).thenReturn(Optional.empty());
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenReturn(
            new TenantPermissionPolicies()
                .tenantId(42L)
                .policies(Map.of())
                .caseHandoverPolicies(policies));

    assertThat(service.refresh(42L)).isSameAs(policies);

    ArgumentCaptor<TenantCaseHandoverPolicyCache> saved =
        ArgumentCaptor.forClass(TenantCaseHandoverPolicyCache.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().getTenantId()).isEqualTo(42L);
    assertThat(saved.getValue().getRefreshedAt()).isEqualTo(LocalDateTime.of(2026, 8, 16, 10, 0));
    assertThat(saved.getValue().getStaleSince()).isNull();
  }

  @Test
  void refresh_keepsLastKnownGoodAndMarksItStaleWhenTenantServiceFails() {
    var cache =
        TenantCaseHandoverPolicyCache.builder()
            .tenantId(42L)
            .policies("{\"reasons\":{}}")
            .refreshedAt(LocalDateTime.of(2026, 8, 16, 9, 0))
            .build();
    when(repository.findById(42L)).thenReturn(Optional.of(cache));
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenThrow(new RestClientException("TenantService unavailable"));

    assertThat(service.refresh(42L).getReasons()).isEmpty();
    assertThat(cache.getStaleSince()).isEqualTo(LocalDateTime.of(2026, 8, 16, 10, 0));
    verify(repository).save(cache);
  }

  @Test
  void refresh_failsClosedWithoutAValidSnapshot() {
    when(repository.findById(42L)).thenReturn(Optional.empty());
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenThrow(new RestClientException("TenantService unavailable"));

    assertThatThrownBy(() -> service.refresh(42L))
        .isInstanceOf(RestClientException.class)
        .hasMessageContaining("unavailable");
  }

  @Test
  void refresh_rejectsAMismatchedTenantResponseWithoutPersistingIt() {
    when(repository.findById(42L)).thenReturn(Optional.empty());
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenReturn(
            new TenantPermissionPolicies()
                .tenantId(43L)
                .policies(Map.of())
                .caseHandoverPolicies(new CaseHandoverPolicies().reasons(Map.of())));

    assertThatThrownBy(() -> service.refresh(42L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("matching");
    verify(repository, never()).save(any());
  }

  @Test
  void refresh_usesThePersistedSnapshotWhenAnotherReplicaOwnsTheTenantLease() {
    var cache =
        TenantCaseHandoverPolicyCache.builder()
            .tenantId(42L)
            .policies("{\"reasons\":{}}")
            .refreshedAt(LocalDateTime.of(2026, 8, 16, 9, 0))
            .build();
    when(repository.findById(42L)).thenReturn(Optional.of(cache));
    when(scheduledTaskClaimService.tryClaim(anyString(), any())).thenReturn(false);

    assertThat(service.refresh(42L).getReasons()).isEmpty();

    verify(tenantControllerApi, never()).getTenantPermissionPolicies(any());
    verify(repository, never()).save(any());
  }

  @Test
  void refresh_signalsARetryableConflictWhenAnotherReplicaOwnsTheLeaseAndNoSnapshotExists() {
    when(repository.findById(42L)).thenReturn(Optional.empty());
    when(scheduledTaskClaimService.tryClaim(anyString(), any())).thenReturn(false);

    assertThatThrownBy(() -> service.refresh(42L))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageContaining("retry");
    verify(tenantControllerApi, never()).getTenantPermissionPolicies(any());
  }

  @Test
  void refresh_passesTheConfiguredClaimDurationToTheLease() {
    ReflectionTestUtils.setField(service, "policyRefreshClaimDuration", Duration.ofMinutes(5));
    var policies = new CaseHandoverPolicies().reasons(Map.of());
    when(repository.findById(42L)).thenReturn(Optional.empty());
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenReturn(
            new TenantPermissionPolicies()
                .tenantId(42L)
                .policies(Map.of())
                .caseHandoverPolicies(policies));

    service.refresh(42L);

    verify(scheduledTaskClaimService)
        .tryClaim("case-handover-policy-refresh-42", Duration.ofMinutes(5));
  }

  @Test
  void getEffective_refreshesTheTenantWhenNoSnapshotIsCached() {
    var policies = new CaseHandoverPolicies().reasons(Map.of());
    when(repository.findById(42L)).thenReturn(Optional.empty());
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenReturn(
            new TenantPermissionPolicies()
                .tenantId(42L)
                .policies(Map.of())
                .caseHandoverPolicies(policies));

    assertThat(service.getEffective(42L)).isSameAs(policies);

    verify(repository).save(any(TenantCaseHandoverPolicyCache.class));
  }

  /**
   * The production entry points are {@code getEffective} and the scheduled sweep; {@code refresh}
   * is only ever self-invoked from them, where its own annotation is inert. The transaction must
   * therefore open at the proxy boundary of the entry points.
   */
  @Test
  void entryPoints_carryTheTransactionAnnotationAtTheProxyBoundary() throws Exception {
    assertThat(
            CaseHandoverPolicyCacheService.class
                .getMethod("getEffective", Long.class)
                .isAnnotationPresent(Transactional.class))
        .isTrue();
    assertThat(
            CaseHandoverPolicyCacheService.class
                .getDeclaredMethod("refreshKnownTenants")
                .isAnnotationPresent(Transactional.class))
        .isTrue();
  }
}
