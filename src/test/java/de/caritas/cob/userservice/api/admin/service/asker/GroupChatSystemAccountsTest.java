package de.caritas.cob.userservice.api.admin.service.asker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupChatSystemAccountsTest {

  @Mock private TenantService tenantService;
  @InjectMocks private GroupChatSystemAccounts accounts;

  @Test
  void isTechnical_Should_recogniseTheTenantScopedAndLegacySystemUsers() {
    assertThat(GroupChatSystemAccounts.isTechnical("group-chat-system-12")).isTrue();
    assertThat(GroupChatSystemAccounts.isTechnical("group-chat-system")).isTrue();
    assertThat(GroupChatSystemAccounts.isTechnical("0f2cca9c-9303-4791-a0a5-a1ce16f1524f"))
        .isFalse();
    assertThat(GroupChatSystemAccounts.isTechnical(null)).isFalse();
  }

  @Test
  void tenantsStillPresent_Should_returnTheTenantsTheTenantServiceKnows() {
    when(tenantService.getRestrictedTenantData(Set.of(3L, 4L)))
        .thenReturn(List.of(new RestrictedTenantDTO().id(3L)));

    assertThat(accounts.tenantsStillPresent(Set.of(3L, 4L))).containsExactly(3L);
  }

  @Test
  void mayDelete_Should_allowOnlyTheAccountOfAGoneTenant() {
    when(tenantService.getRestrictedTenantData(anySet())).thenReturn(List.of());

    assertThat(accounts.mayDelete("group-chat-system-4", 4L)).isTrue();
  }

  @Test
  void mayDelete_Should_refuse_When_tenantStillExists() {
    when(tenantService.getRestrictedTenantData(anySet()))
        .thenReturn(List.of(new RestrictedTenantDTO().id(4L)));

    assertThat(accounts.mayDelete("group-chat-system-4", 4L)).isFalse();
  }

  @Test
  void mayDelete_Should_refuseThePlatformsOwnAccountsWithoutAsking() {
    assertThat(accounts.mayDelete("group-chat-system-default", null)).isFalse();
    assertThat(accounts.mayDelete("group-chat-system", 0L)).isFalse();
    verifyNoInteractions(tenantService);
  }

  @Test
  void mayDelete_Should_allowEveryPerson() {
    assertThat(accounts.mayDelete("0f2cca9c-9303-4791-a0a5-a1ce16f1524f", 4L)).isTrue();
    verifyNoInteractions(tenantService);
  }
}
