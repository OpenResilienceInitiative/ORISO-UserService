package de.caritas.cob.userservice.api.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class StatisticsFeatureGateTest {

  @Mock private TenantService tenantService;

  private StatisticsFeatureGate gate;

  @BeforeEach
  void setUp() {
    gate = new StatisticsFeatureGate(tenantService);
    setField(gate, "multitenancy", true);
  }

  @Test
  void isEnabled_isTrue_when_theOrganisationSwitchIsOn() {
    when(tenantService.getRestrictedTenantDataFresh(3L)).thenReturn(tenant(3L, true));

    assertThat(gate.isEnabledForTenant(3L)).isTrue();
  }

  @Test
  void isEnabled_isFalse_when_theOrganisationSwitchIsOff() {
    when(tenantService.getRestrictedTenantDataFresh(3L)).thenReturn(tenant(3L, false));

    assertThat(gate.isEnabledForTenant(3L)).isFalse();
  }

  /** An unset switch is not a yes: counting needs an explicit decision of the organisation. */
  @Test
  void isEnabled_isFalse_when_theSwitchIsUnset() {
    when(tenantService.getRestrictedTenantDataFresh(3L)).thenReturn(tenant(3L, null));

    assertThat(gate.isEnabledForTenant(3L)).isFalse();
  }

  @Test
  void isEnabled_isFalse_when_noSettingsAreReturned() {
    var tenant = new RestrictedTenantDTO();
    tenant.setId(3L);
    when(tenantService.getRestrictedTenantDataFresh(3L)).thenReturn(tenant);

    assertThat(gate.isEnabledForTenant(3L)).isFalse();
  }

  @Test
  void isEnabled_isFalse_when_theTenantServiceCannotBeReached() {
    when(tenantService.getRestrictedTenantDataFresh(3L)).thenThrow(new RestClientException("down"));

    assertThat(gate.isEnabledForTenant(3L)).isFalse();
  }

  @Test
  void isEnabled_isFalse_when_theAnswerBelongsToAnotherTenant() {
    when(tenantService.getRestrictedTenantDataFresh(3L)).thenReturn(tenant(4L, true));

    assertThat(gate.isEnabledForTenant(3L)).isFalse();
  }

  @Test
  void isEnabled_isFalse_forTheTechnicalTenantWithoutAskingTheTenantService() {
    assertThat(gate.isEnabledForTenant(0L)).isFalse();

    verifyNoInteractions(tenantService);
  }

  @Test
  void isEnabled_isFalse_withoutTenant_when_multitenancyIsOn() {
    assertThat(gate.isEnabledForTenant(null)).isFalse();

    verifyNoInteractions(tenantService);
  }

  @Test
  void isEnabled_usesTheSingleTenancyTenant_withoutTenant_when_multitenancyIsOff() {
    setField(gate, "multitenancy", false);
    when(tenantService.getSingleTenancyTenantDataFresh()).thenReturn(tenant(1L, true));

    assertThat(gate.isEnabledForTenant(null)).isTrue();
    verify(tenantService).getSingleTenancyTenantDataFresh();
  }

  private static RestrictedTenantDTO tenant(Long id, Boolean statisticsEnabled) {
    var settings = new Settings();
    settings.setFeatureStatisticsEnabled(statisticsEnabled);
    var tenant = new RestrictedTenantDTO();
    tenant.setId(id);
    tenant.setSettings(settings);
    return tenant;
  }
}
