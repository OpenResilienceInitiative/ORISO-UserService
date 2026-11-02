package de.caritas.cob.userservice.api.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class StatisticsFeatureGateTest {

  @Mock private TenantService tenantService;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-03T10:00:00Z"));

  private StatisticsFeatureGate gate;

  @BeforeEach
  void setUp() {
    gate = new StatisticsFeatureGate(tenantService, clock);
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

  /** The notification worker calls the gate once per message: one lookup serves the next ones. */
  @Test
  void isEnabled_asksTheTenantServiceOnlyOnce_forRepeatedMessagesOfOneTenant() {
    when(tenantService.getRestrictedTenantDataFresh(3L)).thenReturn(tenant(3L, true));

    for (int i = 0; i < 20; i++) {
      assertThat(gate.isEnabledForTenant(3L)).isTrue();
    }

    verify(tenantService, times(1)).getRestrictedTenantDataFresh(3L);
  }

  @Test
  void isEnabled_remembersAnOffAnswerToo() {
    when(tenantService.getRestrictedTenantDataFresh(3L)).thenReturn(tenant(3L, false));

    assertThat(gate.isEnabledForTenant(3L)).isFalse();
    assertThat(gate.isEnabledForTenant(3L)).isFalse();

    verify(tenantService, times(1)).getRestrictedTenantDataFresh(3L);
  }

  @Test
  void isEnabled_keepsTenantsApart() {
    when(tenantService.getRestrictedTenantDataFresh(3L)).thenReturn(tenant(3L, true));
    when(tenantService.getRestrictedTenantDataFresh(4L)).thenReturn(tenant(4L, false));

    assertThat(gate.isEnabledForTenant(3L)).isTrue();
    assertThat(gate.isEnabledForTenant(4L)).isFalse();
    assertThat(gate.isEnabledForTenant(3L)).isTrue();

    verify(tenantService, times(1)).getRestrictedTenantDataFresh(3L);
    verify(tenantService, times(1)).getRestrictedTenantDataFresh(4L);
  }

  /** After the minute is over a switch-off of the organisation takes effect. */
  @Test
  void isEnabled_refreshesTheAnswerAfterItExpired() {
    when(tenantService.getRestrictedTenantDataFresh(3L))
        .thenReturn(tenant(3L, true), tenant(3L, false));

    assertThat(gate.isEnabledForTenant(3L)).isTrue();
    clock.advance(Duration.ofSeconds(59));
    assertThat(gate.isEnabledForTenant(3L)).isTrue();
    clock.advance(Duration.ofSeconds(2));
    assertThat(gate.isEnabledForTenant(3L)).isFalse();

    verify(tenantService, times(2)).getRestrictedTenantDataFresh(3L);
  }

  /** A failure is remembered as "off" briefly, never as "on", and does not hammer the service. */
  @Test
  void isEnabled_remembersAFailureAsOff_forAShortTime_andThenAsksAgain() {
    when(tenantService.getRestrictedTenantDataFresh(3L))
        .thenThrow(new RestClientException("down"))
        .thenReturn(tenant(3L, true));

    assertThat(gate.isEnabledForTenant(3L)).isFalse();
    assertThat(gate.isEnabledForTenant(3L)).isFalse();
    verify(tenantService, times(1)).getRestrictedTenantDataFresh(3L);

    clock.advance(Duration.ofSeconds(31));
    assertThat(gate.isEnabledForTenant(3L)).isTrue();
    verify(tenantService, times(2)).getRestrictedTenantDataFresh(3L);
  }

  @Test
  void isEnabled_forwardsAFailureAfterAnOnAnswerAsOff() {
    when(tenantService.getRestrictedTenantDataFresh(3L))
        .thenReturn(tenant(3L, true))
        .thenThrow(new RestClientException("down"));

    assertThat(gate.isEnabledForTenant(3L)).isTrue();
    clock.advance(Duration.ofSeconds(61));
    assertThat(gate.isEnabledForTenant(3L)).isFalse();
  }

  @Test
  void isEnabled_remembersTheSingleTenancyAnswer() {
    setField(gate, "multitenancy", false);
    when(tenantService.getSingleTenancyTenantDataFresh()).thenReturn(tenant(1L, true));

    assertThat(gate.isEnabledForTenant(null)).isTrue();
    assertThat(gate.isEnabledForTenant(null)).isTrue();

    verify(tenantService, times(1)).getSingleTenancyTenantDataFresh();
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
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
