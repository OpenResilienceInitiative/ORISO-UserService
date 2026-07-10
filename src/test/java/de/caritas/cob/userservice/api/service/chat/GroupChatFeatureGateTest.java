package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupChatFeatureGateTest {

  @Mock private TenantService tenantService;
  @Mock private Consultant consultant;
  private GroupChatFeatureGate gate;

  @BeforeEach
  void setUp() {
    gate = new GroupChatFeatureGate(tenantService);
  }

  @Test
  void enabledTenantMayCreateSelfHelpGroupSeries() {
    when(consultant.getTenantId()).thenReturn(7L);
    when(tenantService.getRestrictedTenantData(7L))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(7L)
                .name("Tenant")
                .settings(new RestrictedTenantSettings().featureGroupChatV2Enabled(true)));

    assertThatCode(() -> gate.requireEnabled(consultant)).doesNotThrowAnyException();
  }

  @Test
  void disabledTenantMayNotCreateSelfHelpGroupSeries() {
    when(consultant.getTenantId()).thenReturn(7L);
    when(tenantService.getRestrictedTenantData(7L))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(7L)
                .name("Tenant")
                .settings(new RestrictedTenantSettings().featureGroupChatV2Enabled(false)));

    assertThatThrownBy(() -> gate.requireEnabled(consultant))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void missingTenantContextFailsClosed() {
    assertThatThrownBy(() -> gate.requireEnabled(null)).isInstanceOf(ForbiddenException.class);
  }
}
