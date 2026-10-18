package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeAudience;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeAudience.DryRun;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ServiceNoticeDryRunControllerTest {
  private final ServiceNoticeAudience audience = mock(ServiceNoticeAudience.class);
  private final Set<String> platformRolePair =
      Set.of(UserRole.AGENCY_ADMIN.getValue(), UserRole.TENANT_ADMIN.getValue());

  @Test
  void onlyThePlatformAdminMayCountTheAudience() {
    for (AuthenticatedUser caller :
        new AuthenticatedUser[] {
          caller(7L, platformRolePair),
          caller(0L, Set.of(UserRole.TENANT_ADMIN.getValue())),
          caller(0L, Set.of(UserRole.RESTRICTED_AGENCY_ADMIN.getValue())),
          caller(0L, Set.of())
        }) {
      var controller = new ServiceNoticeDryRunController(caller, audience);
      assertThatThrownBy(() -> controller.dryRun("maintenance-1"))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.FORBIDDEN));
    }
    verifyNoInteractions(audience);
  }

  @Test
  void platformAdminSeesTheCountsUncached() {
    var dryRun = new DryRun("maintenance-1", "AGENCY_ADMINS", 12, 9, 2, 1, 0);
    when(audience.dryRun("maintenance-1")).thenReturn(dryRun);

    var response =
        new ServiceNoticeDryRunController(caller(0L, platformRolePair), audience)
            .dryRun("maintenance-1");

    assertThat(response.getBody()).isEqualTo(dryRun);
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
  }

  @Test
  void anUnknownDraftIsNotFound() {
    when(audience.dryRun("missing")).thenThrow(new NoSuchElementException("missing"));
    var controller = new ServiceNoticeDryRunController(caller(0L, platformRolePair), audience);

    assertThatThrownBy(() -> controller.dryRun("missing"))
        .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.NOT_FOUND));
  }

  private static Object status(Throwable error) {
    return ((ResponseStatusException) error).getStatusCode();
  }

  private static AuthenticatedUser caller(Long tenantId, Set<String> roles) {
    return new AuthenticatedUser("operator-1", "operator", roles, "", tenantId, Set.of());
  }
}
