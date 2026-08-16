package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.model.TenantCaseHandoverPolicyCache;
import de.caritas.cob.userservice.api.port.out.TenantCaseHandoverPolicyCacheRepository;
import de.caritas.cob.userservice.tenantadminservice.generated.web.TenantControllerApi;
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
  @Mock private TenantAdminServiceApiControllerFactory tenantServiceFactory;
  @Mock private TenantControllerApi tenantControllerApi;
  private final Clock clock = Clock.fixed(Instant.parse("2026-08-16T10:00:00Z"), ZoneOffset.UTC);
  private CaseHandoverPolicyCacheService service;

  @BeforeEach
  void setUp() {
    service = new CaseHandoverPolicyCacheService(repository, tenantServiceFactory, clock);
    when(tenantServiceFactory.createControllerApi()).thenReturn(tenantControllerApi);
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
}
