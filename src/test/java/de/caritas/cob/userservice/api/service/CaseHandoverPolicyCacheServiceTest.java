package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.TenantCaseHandoverPolicyCache;
import de.caritas.cob.userservice.api.port.out.TenantCaseHandoverPolicyCacheRepository;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverPolicies;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantPermissionPolicies;
import java.time.Clock;
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
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class CaseHandoverPolicyCacheServiceTest {

  @Mock private TenantCaseHandoverPolicyCacheRepository repository;
  @Mock private TenantCaseHandoverPolicyReadClient tenantControllerApi;
  @Mock private ScheduledTaskClaimService scheduledTaskClaimService;
  private final Clock clock = Clock.fixed(Instant.parse("2026-08-16T10:00:00Z"), ZoneOffset.UTC);
  private CaseHandoverPolicyCacheService service;

  @BeforeEach
  void setUp() {
    service =
        new CaseHandoverPolicyCacheService(
            repository, tenantControllerApi, scheduledTaskClaimService, clock);
    when(scheduledTaskClaimService.tryClaim(anyString(), any())).thenReturn(true);
  }

  @Test
  void publicScheduledEntryPreservesSnapshotWhenAnotherReplicaOwnsLease() throws Exception {
    assertThat(
            CaseHandoverPolicyCacheService.class.getMethod("refreshKnownTenants").getReturnType())
        .isEqualTo(void.class);
    var cache =
        TenantCaseHandoverPolicyCache.builder().tenantId(42L).policies("{\"reasons\":{}}").build();
    when(repository.findAll()).thenReturn(java.util.List.of(cache));
    when(repository.findById(42L)).thenReturn(Optional.of(cache));
    when(scheduledTaskClaimService.tryClaim(
            "case-handover-policy-refresh-42", java.time.Duration.ofMinutes(1)))
        .thenReturn(false);
    service.refreshKnownTenants();
    verify(tenantControllerApi, never()).getTenantPermissionPolicies(any());
    verify(repository, never()).save(any());
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
  void refresh_keeps180AndHolidayConsentFalseInLastKnownGoodDuringOutage() {
    var cache =
        TenantCaseHandoverPolicyCache.builder()
            .tenantId(42L)
            .policies(
                "{\"reasons\":{\"COUNSELLOR_ASKED_FOR_ADVICE\":{\"code\":\"COUNSELLOR_ASKED_FOR_ADVICE\",\"maxAccessDurationMinutes\":{\"value\":180}},\"COUNSELLOR_ON_HOLIDAY\":{\"code\":\"COUNSELLOR_ON_HOLIDAY\",\"clientConsentRequired\":{\"value\":false}}}}")
            .refreshedAt(LocalDateTime.of(2026, 8, 16, 9, 0))
            .build();
    when(repository.findById(42L)).thenReturn(Optional.of(cache));
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenThrow(new RestClientException("Synthetic outage"));

    var reasons = service.refresh(42L).getReasons();

    assertThat(reasons.get("COUNSELLOR_ASKED_FOR_ADVICE").getMaxAccessDurationMinutes().getValue())
        .isEqualTo(180);
    assertThat(reasons.get("COUNSELLOR_ON_HOLIDAY").getClientConsentRequired().getValue())
        .isFalse();
    assertThat(cache.getStaleSince()).isNotNull();
  }

  @Test
  void failedRefreshNeverLogsDownstreamBodyOrThrowable() {
    var cache =
        TenantCaseHandoverPolicyCache.builder().tenantId(42L).policies("{\"reasons\":{}}").build();
    when(repository.findById(42L)).thenReturn(Optional.of(cache));
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenThrow(new RestClientException("synthetic-sensitive-provider-body"));
    try (var logs =
        de.caritas.cob.userservice.testutils.LogbackCaptor.forClass(
            CaseHandoverPolicyCacheService.class)) {
      service.refresh(42L);
      assertThat(logs.events())
          .allSatisfy(
              event -> {
                assertThat(event.getFormattedMessage())
                    .doesNotContain("synthetic-sensitive-provider-body");
                assertThat(event.getThrowableProxy()).isNull();
              });
      assertThat(logs.hasWarnLog()).isTrue();
    }
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
  void refresh_rethrowsTheUpstreamFailureWhenTheSnapshotItselfIsUnreadable() {
    var cache =
        TenantCaseHandoverPolicyCache.builder()
            .tenantId(42L)
            .policies("{ this is not valid json")
            .refreshedAt(LocalDateTime.of(2026, 8, 16, 9, 0))
            .build();
    when(repository.findById(42L)).thenReturn(Optional.of(cache));
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenThrow(new RestClientException("TenantService unavailable"));

    // The real cause must survive. Raising "Cached Case Handover policy is invalid" from inside
    // the fallback would hide the outage and defeat the last-known-good contract.
    assertThatThrownBy(() -> service.refresh(42L))
        .isInstanceOf(RestClientException.class)
        .hasMessageContaining("unavailable");
  }

  @Test
  void getEffective_refreshesInsteadOfFailingWhenTheStoredSnapshotIsUnreadable() {
    var cache =
        TenantCaseHandoverPolicyCache.builder().tenantId(42L).policies("<<corrupt>>").build();
    var policies = new CaseHandoverPolicies().reasons(Map.of());
    when(repository.findById(42L)).thenReturn(Optional.of(cache));
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenReturn(
            new TenantPermissionPolicies()
                .tenantId(42L)
                .policies(Map.of())
                .caseHandoverPolicies(policies));

    assertThat(service.getEffective(42L)).isSameAs(policies);
  }

  @Test
  void refreshKnownTenants_continuesAfterATenantThatFails() {
    var failing =
        TenantCaseHandoverPolicyCache.builder().tenantId(41L).policies("{\"reasons\":{}}").build();
    var healthy =
        TenantCaseHandoverPolicyCache.builder().tenantId(42L).policies("{\"reasons\":{}}").build();
    when(repository.findAll()).thenReturn(java.util.List.of(failing, healthy));
    // No persisted snapshot for 41: refresh has nothing to fall back on and propagates.
    when(repository.findById(41L)).thenReturn(Optional.empty());
    when(tenantControllerApi.getTenantPermissionPolicies(41L))
        .thenThrow(new RestClientException("TenantService unavailable"));
    when(repository.findById(42L)).thenReturn(Optional.of(healthy));
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenReturn(
            new TenantPermissionPolicies()
                .tenantId(42L)
                .policies(Map.of())
                .caseHandoverPolicies(new CaseHandoverPolicies().reasons(Map.of())));

    service.refreshKnownTenants();

    // The second tenant must still be refreshed; an aborted sweep would leave it silently aging.
    verify(tenantControllerApi).getTenantPermissionPolicies(42L);
    verify(repository).save(healthy);
  }
}
