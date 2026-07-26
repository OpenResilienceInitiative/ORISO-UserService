package de.caritas.cob.userservice.api.service.teamdiscussion;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * US#473 / ADR-016 — the Team-Besprechung gate prefers the per-tenant flag and falls back to the
 * deploy property when there is no tenant context (single-tenant deploy).
 */
@ExtendWith(MockitoExtension.class)
class TeamDiscussionFeatureGateTest {

  @Mock private TenantService tenantService;

  private TeamDiscussionFeatureGate gateWithProperty(boolean deployDefault) {
    var gate = new TeamDiscussionFeatureGate(tenantService);
    ReflectionTestUtils.setField(gate, "teamDiscussionEnabled", deployDefault);
    return gate;
  }

  private RestrictedTenantDTO tenant(Long id, Boolean flag) {
    return new RestrictedTenantDTO()
        .id(id)
        .name("Tenant")
        .settings(new RestrictedTenantSettings().featureTeamDiscussionEnabled(flag));
  }

  @Test
  void tenantFlagTrue_enables_evenIfDeployPropertyFalse() {
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant(7L, true));
    var gate = gateWithProperty(false);
    assertThatCode(() -> gate.requireEnabled(7L)).doesNotThrowAnyException();
  }

  @Test
  void tenantFlagFalse_disables_evenIfDeployPropertyTrue() {
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant(7L, false));
    var gate = gateWithProperty(true);
    assertThatThrownBy(() -> gate.requireEnabled(7L)).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void noTenantContext_fallsBackToDeployProperty_enabled() {
    var gate = gateWithProperty(true);
    assertThatCode(() -> gate.requireEnabled(null)).doesNotThrowAnyException();
  }

  @Test
  void noTenantContext_fallsBackToDeployProperty_disabled() {
    var gate = gateWithProperty(false);
    assertThatThrownBy(() -> gate.requireEnabled(null)).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void tenantWithoutSettings_fallsBackToDeployProperty() {
    when(tenantService.getRestrictedTenantDataFresh(7L))
        .thenReturn(new RestrictedTenantDTO().id(7L).name("Tenant").settings(null));
    var gate = gateWithProperty(true);
    assertThatCode(() -> gate.requireEnabled(7L)).doesNotThrowAnyException();
  }

  @Test
  void isEnabled_reflectsTenantFlag() {
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant(7L, false));
    var gate = gateWithProperty(true);
    org.assertj.core.api.Assertions.assertThat(gate.isEnabled(7L)).isFalse();
  }
}
