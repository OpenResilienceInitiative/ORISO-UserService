package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.TenantCaseHandoverPolicyCache;
import de.caritas.cob.userservice.api.port.out.TenantCaseHandoverPolicyCacheRepository;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class CaseHandoverPolicyCacheServiceTest {

  @Mock private TenantCaseHandoverPolicyCacheRepository repository;
  @Mock private TenantCaseHandoverPolicyReadClient tenantControllerApi;
  @Mock private ScheduledTaskClaimService scheduledTaskClaimService;
  @Mock private PlatformTransactionManager transactionManager;
  @Mock private TransactionStatus transactionStatus;
  private final Clock clock = Clock.fixed(Instant.parse("2026-08-16T10:00:00Z"), ZoneOffset.UTC);
  private CaseHandoverPolicyCacheService service;

  @BeforeEach
  void setUp() {
    service =
        new CaseHandoverPolicyCacheService(
            repository, tenantControllerApi, scheduledTaskClaimService, clock, transactionManager);
    lenient().when(scheduledTaskClaimService.tryClaim(anyString(), any())).thenReturn(true);
    lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "true,ENFORCED",
    "false,ENFORCED",
    "true,SUGGESTED",
    "false,SUGGESTED"
  })
  void runtimeReadsTheCanonicalMasterValueInBothModes(boolean enabled, String mode) {
    when(repository.findById(42L)).thenReturn(Optional.empty());
    var response = authoritative(42L, enabled);
    response
        .getPolicies()
        .get("caseHandoverEnabled")
        .setMode(
            de.caritas.cob.userservice.tenantadminservice.generated.web.model.PermissionPolicyMode
                .valueOf(mode));
    when(tenantControllerApi.getTenantPermissionPolicies(42L)).thenReturn(response);

    assertThat(service.getCurrent(42L).enabled()).isEqualTo(enabled);
    var saved = ArgumentCaptor.forClass(TenantCaseHandoverPolicyCache.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().getPolicies())
        .contains("caseHandoverEnabled", "caseHandoverPolicies", "tenantId");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void freshAuthoritySurvivesCacheReadFailure(boolean enabled) {
    when(repository.findById(42L))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("cache offline"));
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenReturn(authoritative(42L, enabled));

    assertThat(service.getCurrent(42L).enabled()).isEqualTo(enabled);
    verify(tenantControllerApi).getTenantPermissionPolicies(42L);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void freshAuthoritySurvivesConcurrentFirstCacheInsert(boolean enabled) {
    when(repository.findById(42L)).thenReturn(Optional.empty());
    when(repository.save(any()))
        .thenThrow(new org.springframework.dao.DuplicateKeyException("concurrent first insert"));
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenReturn(authoritative(42L, enabled));

    assertThat(service.getCurrent(42L).enabled()).isEqualTo(enabled);
    verify(tenantControllerApi).getTenantPermissionPolicies(42L);
  }

  @Test
  void freshAuthorityDoesNotHideAnUnexpectedRepositoryProgrammingFailure() {
    when(repository.findById(42L)).thenReturn(Optional.empty());
    when(repository.save(any())).thenThrow(new IllegalArgumentException("programming failure"));
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenReturn(authoritative(42L, false));

    assertThatThrownBy(() -> service.getCurrent(42L)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void freshAuthorityDoesNotHideSerializationFailure() throws Exception {
    var mapper = org.mockito.Mockito.mock(com.fasterxml.jackson.databind.ObjectMapper.class);
    ReflectionTestUtils.setField(service, "objectMapper", mapper);
    var authority = authoritative(42L, false);
    when(tenantControllerApi.getTenantPermissionPolicies(42L)).thenReturn(authority);
    when(mapper.writeValueAsString(authority))
        .thenThrow(
            new com.fasterxml.jackson.core.JsonProcessingException("serialization failure") {});

    assertThatThrownBy(() -> service.getCurrent(42L))
        .isInstanceOf(ServiceUnavailableException.class);
    org.mockito.Mockito.verifyNoInteractions(repository);
  }

  @Test
  void runtimeFreshReadObservesOnOffOnWithoutARefreshLease() {
    var stored = useInMemorySnapshots();
    lenient().when(scheduledTaskClaimService.tryClaim(anyString(), any())).thenReturn(false);
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenReturn(authoritative(42L, true), authoritative(42L, false), authoritative(42L, true));

    assertThat(service.getCurrent(42L).enabled()).isTrue();
    assertThat(service.getCurrent(42L).enabled()).isFalse();
    assertThat(service.getCurrent(42L).enabled()).isTrue();
    verify(tenantControllerApi, org.mockito.Mockito.times(3)).getTenantPermissionPolicies(42L);
    assertThat(stored.get(42L).getStaleSince()).isNull();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void runtimeOutageRetainsValidatedFullMasterAndMarksItStale(boolean enabled) {
    var stored = useInMemorySnapshots();
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenReturn(authoritative(42L, enabled))
        .thenThrow(new RestClientException("synthetic-sensitive-provider-body"));
    service.getCurrent(42L);

    assertThat(service.getCurrent(42L).enabled()).isEqualTo(enabled);
    assertThat(stored.get(42L).getStaleSince()).isEqualTo(LocalDateTime.now(clock));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "true,save",
    "false,save",
    "true,commit",
    "false,commit"
  })
  void runtimeOutageRetainsValidatedMasterWhenStaleMarkerPersistenceFails(
      boolean enabled, String failureBoundary) throws Exception {
    var cache = fullSnapshot(enabled);
    when(repository.findById(42L)).thenReturn(Optional.of(cache));
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenThrow(new RestClientException("synthetic-sensitive-provider-body"));
    if (failureBoundary.equals("save")) {
      when(repository.save(any()))
          .thenThrow(
              new org.springframework.dao.DataAccessResourceFailureException(
                  "synthetic-sensitive-database-body"));
    } else {
      org.mockito.Mockito.doNothing()
          .doThrow(
              new org.springframework.transaction.TransactionSystemException(
                  "synthetic-sensitive-database-body"))
          .when(transactionManager)
          .commit(transactionStatus);
    }

    try (var logs =
        de.caritas.cob.userservice.testutils.LogbackCaptor.forClass(
            CaseHandoverPolicyCacheService.class)) {
      var current = service.getCurrent(42L);
      assertThat(current.enabled()).isEqualTo(enabled);
      if (enabled) current.requireEnabled();
      else
        assertThatThrownBy(current::requireEnabled)
            .isInstanceOf(
                de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
      verify(repository).save(cache);
      verify(
              transactionManager,
              org.mockito.Mockito.times(failureBoundary.equals("commit") ? 2 : 1))
          .commit(transactionStatus);
      assertThat(logs.events())
          .allSatisfy(
              event -> {
                assertThat(event.getFormattedMessage())
                    .doesNotContain(
                        "synthetic-sensitive-provider-body", "synthetic-sensitive-database-body");
                assertThat(event.getThrowableProxy()).isNull();
              });
      assertThat(logs.hasWarnLog()).isTrue();
    }
  }

  @Test
  void runtimeOutageStillFailsClosedWhenTheCacheCannotBeRead() {
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenThrow(new RestClientException("synthetic-sensitive-provider-body"));
    when(repository.findById(42L))
        .thenThrow(
            new org.springframework.dao.DataAccessResourceFailureException(
                "synthetic-sensitive-database-body"));

    assertThatThrownBy(() -> service.getCurrent(42L))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("Tenant Case Handover policy is unavailable")
        .hasNoCause();
    verify(repository, never()).save(any());
  }

  @Test
  void staleMarkerDoesNotHideAnUnexpectedProgrammingFailure() throws Exception {
    when(repository.findById(42L)).thenReturn(Optional.of(fullSnapshot(true)));
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenThrow(new RestClientException("provider offline"));
    when(repository.save(any())).thenThrow(new IllegalArgumentException("programming failure"));

    assertThatThrownBy(() -> service.getCurrent(42L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("programming failure");
  }

  private TenantCaseHandoverPolicyCache fullSnapshot(boolean enabled) throws Exception {
    return TenantCaseHandoverPolicyCache.builder()
        .tenantId(42L)
        .policies(
            new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(authoritative(42L, enabled)))
        .refreshedAt(LocalDateTime.now(clock))
        .build();
  }

  @Test
  void runtimeTenantIsolationSurvivesRestartAndTransportFailure() {
    var stored = useInMemorySnapshots();
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenReturn(authoritative(42L, false));
    when(tenantControllerApi.getTenantPermissionPolicies(43L)).thenReturn(authoritative(43L, true));
    service.getCurrent(42L);
    service.getCurrent(43L);
    when(tenantControllerApi.getTenantPermissionPolicies(any()))
        .thenThrow(new RestClientException("offline"));
    var restarted =
        new CaseHandoverPolicyCacheService(
            repository, tenantControllerApi, scheduledTaskClaimService, clock, transactionManager);

    assertThat(restarted.getCurrent(42L).enabled()).isFalse();
    assertThat(restarted.getCurrent(43L).enabled()).isTrue();
    assertThat(stored.keySet()).containsExactlyInAnyOrder(42L, 43L);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"absent", "legacy", "wrongTenant", "malformed"})
  void runtimeOutageCannotUseMissingLegacyWrongTenantOrMalformedAuthority(String kind)
      throws Exception {
    var stored = useInMemorySnapshots();
    if (!kind.equals("absent")) {
      String json =
          kind.equals("legacy")
              ? "{\"reasons\":{}}"
              : kind.equals("malformed")
                  ? "invalid json"
                  : new com.fasterxml.jackson.databind.ObjectMapper()
                      .writeValueAsString(authoritative(43L, true));
      stored.put(42L, TenantCaseHandoverPolicyCache.builder().tenantId(42L).policies(json).build());
    }
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenThrow(new RestClientException("offline"));

    assertThatThrownBy(() -> service.getCurrent(42L))
        .isInstanceOf(ServiceUnavailableException.class);
    verify(repository, never()).save(any());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"missing", "value", "mode", "tenant", "reasons"})
  void malformedLiveAuthorityFailsClosedInsteadOfUsingAnOlderAllowedSnapshot(String field) {
    useInMemorySnapshots();
    when(tenantControllerApi.getTenantPermissionPolicies(42L)).thenReturn(authoritative(42L, true));
    service.getCurrent(42L);
    var invalid = authoritative(42L, true);
    switch (field) {
      case "missing" -> invalid.setPolicies(Map.of());
      case "value" -> invalid.getPolicies().get("caseHandoverEnabled").setValue(null);
      case "mode" -> invalid.getPolicies().get("caseHandoverEnabled").setMode(null);
      case "tenant" -> invalid.setTenantId(43L);
      case "reasons" -> invalid.setCaseHandoverPolicies(null);
      default -> throw new AssertionError();
    }
    when(tenantControllerApi.getTenantPermissionPolicies(42L)).thenReturn(invalid);

    assertThatThrownBy(() -> service.getCurrent(42L))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void configurationReadDoesNotExposeAFullSnapshotForAnotherTenant() throws Exception {
    var stored = useInMemorySnapshots();
    stored.put(
        42L,
        TenantCaseHandoverPolicyCache.builder()
            .tenantId(42L)
            .policies(
                new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(authoritative(43L, true)))
            .build());
    var actual = authoritative(42L, false);
    when(tenantControllerApi.getTenantPermissionPolicies(42L)).thenReturn(actual);

    assertThat(service.getEffective(42L)).isSameAs(actual.getCaseHandoverPolicies());
    verify(tenantControllerApi).getTenantPermissionPolicies(42L);
    assertThat(stored.get(42L).getPolicies()).contains("\"tenantId\":42");
  }

  private Map<Long, TenantCaseHandoverPolicyCache> useInMemorySnapshots() {
    var stored = new java.util.HashMap<Long, TenantCaseHandoverPolicyCache>();
    when(repository.findById(any()))
        .thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
    lenient()
        .when(repository.save(any()))
        .thenAnswer(
            call -> {
              TenantCaseHandoverPolicyCache value = call.getArgument(0);
              stored.put(value.getTenantId(), value);
              return value;
            });
    return stored;
  }

  private TenantPermissionPolicies authoritative(Long tenantId, boolean enabled) {
    return new TenantPermissionPolicies()
        .tenantId(tenantId)
        .policies(master(enabled))
        .caseHandoverPolicies(new CaseHandoverPolicies().reasons(Map.of()));
  }

  private Map<
          String,
          de.caritas.cob.userservice.tenantadminservice.generated.web.model.BooleanPermissionPolicy>
      master(boolean enabled) {
    return Map.of(
        "caseHandoverEnabled",
        new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                .BooleanPermissionPolicy(null)
            .value(enabled)
            .mode(
                de.caritas.cob.userservice.tenantadminservice.generated.web.model
                    .PermissionPolicyMode.ENFORCED));
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
                .policies(master(true))
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
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageContaining("unavailable");
  }

  @Test
  void refresh_rejectsAMismatchedTenantResponseWithoutPersistingIt() {
    when(repository.findById(42L)).thenReturn(Optional.empty());
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenReturn(
            new TenantPermissionPolicies()
                .tenantId(43L)
                .policies(master(true))
                .caseHandoverPolicies(new CaseHandoverPolicies().reasons(Map.of())));

    assertThatThrownBy(() -> service.refresh(42L))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageContaining("unavailable");
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
                .policies(master(true))
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
                .policies(master(true))
                .caseHandoverPolicies(policies));

    assertThat(service.getEffective(42L)).isSameAs(policies);
    verify(repository).save(any(TenantCaseHandoverPolicyCache.class));
  }

  @Test
  void updateEffectivePreservesUnrelatedTenantPoliciesAndStoresResolvedResponse() {
    var generalPolicy =
        new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                .BooleanPermissionPolicy(null)
            .value(true)
            .mode(
                de.caritas.cob.userservice.tenantadminservice.generated.web.model
                    .PermissionPolicyMode.ENFORCED);
    var current =
        new TenantPermissionPolicies()
            .tenantId(42L)
            .policies(Map.of("somePolicy", generalPolicy, "caseHandoverEnabled", generalPolicy))
            .caseHandoverPolicies(new CaseHandoverPolicies().reasons(Map.of()));
    var requested = new CaseHandoverPolicies().reasons(Map.of());
    when(tenantControllerApi.getTenantPermissionPoliciesAsCaller(42L)).thenReturn(current);
    when(tenantControllerApi.updateTenantPermissionPoliciesAsCaller(42L, current))
        .thenReturn(current);
    when(repository.findById(42L)).thenReturn(Optional.empty());

    assertThat(service.updateEffective(42L, requested).getReasons()).isEmpty();

    // The admin-triggered read-modify-write never runs as the service identity.
    verify(tenantControllerApi, never()).getTenantPermissionPolicies(any());

    assertThat(current.getPolicies()).containsKey("somePolicy");
    assertThat(current.getCaseHandoverPolicies()).isSameAs(requested);
    verify(repository).save(any(TenantCaseHandoverPolicyCache.class));
  }

  @Test
  void refresh_returnsRetryableFailureWhenTheSnapshotItselfIsUnreadable() {
    var cache =
        TenantCaseHandoverPolicyCache.builder()
            .tenantId(42L)
            .policies("{ this is not valid json")
            .refreshedAt(LocalDateTime.of(2026, 8, 16, 9, 0))
            .build();
    when(repository.findById(42L)).thenReturn(Optional.of(cache));
    when(tenantControllerApi.getTenantPermissionPolicies(42L))
        .thenThrow(new RestClientException("TenantService unavailable"));

    assertThatThrownBy(() -> service.refresh(42L))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("Tenant Case Handover policy is unavailable");
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
                .policies(master(true))
                .caseHandoverPolicies(policies));

    assertThat(service.getEffective(42L)).isSameAs(policies);
  }

  /** Provider calls stay outside proxy transactions; short database work uses REQUIRES_NEW. */
  @Test
  void entryPoints_carryTheTransactionAnnotationAtTheProxyBoundary() throws Exception {
    assertThat(
            CaseHandoverPolicyCacheService.class
                .getMethod("getEffective", Long.class)
                .isAnnotationPresent(Transactional.class))
        .isFalse();
    assertThat(
            CaseHandoverPolicyCacheService.class
                .getDeclaredMethod("refreshKnownTenants")
                .isAnnotationPresent(Transactional.class))
        .isFalse();
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
                .policies(master(true))
                .caseHandoverPolicies(new CaseHandoverPolicies().reasons(Map.of())));

    service.refreshKnownTenants();

    // The second tenant must still be refreshed; an aborted sweep would leave it silently aging.
    verify(tenantControllerApi).getTenantPermissionPolicies(42L);
    verify(repository).save(healthy);
    var definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager, org.mockito.Mockito.times(4)).getTransaction(definitions.capture());
    assertThat(definitions.getAllValues())
        .allMatch(
            definition ->
                definition.getPropagationBehavior()
                    == TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }
}
