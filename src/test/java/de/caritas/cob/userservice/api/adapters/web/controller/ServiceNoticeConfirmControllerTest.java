package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.controller.ServiceNoticeConfirmController.ConfirmRequest;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeConfirmation;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeConfirmation.Confirmed;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ServiceNoticeConfirmControllerTest {
  private final ServiceNoticeConfirmation confirmation = mock(ServiceNoticeConfirmation.class);
  private final Set<String> platformRolePair =
      Set.of(UserRole.AGENCY_ADMIN.getValue(), UserRole.TENANT_ADMIN.getValue());
  private final ServiceNoticeConfirmController controller =
      new ServiceNoticeConfirmController(caller(0L, platformRolePair), confirmation);

  @Test
  void onlyThePlatformAdminMayConfirm() {
    for (AuthenticatedUser caller :
        new AuthenticatedUser[] {
          caller(7L, platformRolePair),
          caller(0L, Set.of(UserRole.TENANT_ADMIN.getValue())),
          caller(0L, Set.of(UserRole.RESTRICTED_AGENCY_ADMIN.getValue())),
          caller(0L, Set.of())
        }) {
      assertThatThrownBy(
              () ->
                  new ServiceNoticeConfirmController(caller, confirmation)
                      .confirm("maintenance-1", new ConfirmRequest(3)))
          .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.FORBIDDEN));
    }
    verifyNoInteractions(confirmation);
  }

  @Test
  void confirmationNeedsTheCountTheOperatorSawInTheDryRun() {
    assertThatThrownBy(() -> controller.confirm("maintenance-1", new ConfirmRequest(null)))
        .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.BAD_REQUEST));
    assertThatThrownBy(() -> controller.confirm("maintenance-1", null))
        .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.BAD_REQUEST));
    verifyNoInteractions(confirmation);
  }

  @Test
  void theConfirmedSummaryIsReturnedUncached() {
    var confirmed = new Confirmed("maintenance-1", "CONFIRMED", 3, 2, false);
    when(confirmation.confirm("maintenance-1", 3, "operator-1")).thenReturn(confirmed);

    var response = controller.confirm("maintenance-1", new ConfirmRequest(3));

    assertThat(response.getBody()).isEqualTo(confirmed);
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
  }

  @Test
  void refusalsAreConflictsAndAnotherOperatorIsForbidden() {
    when(confirmation.confirm("stale", 3, "operator-1"))
        .thenThrow(new ServiceNoticeConfirmation.Refused("The audience changed"));
    when(confirmation.confirm("foreign", 3, "operator-1"))
        .thenThrow(new ServiceNoticeConfirmation.NotTheDraftOwner());
    when(confirmation.confirm("missing", 3, "operator-1"))
        .thenThrow(new NoSuchElementException("missing"));

    assertThatThrownBy(() -> controller.confirm("stale", new ConfirmRequest(3)))
        .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.CONFLICT));
    assertThatThrownBy(() -> controller.confirm("foreign", new ConfirmRequest(3)))
        .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.FORBIDDEN));
    assertThatThrownBy(() -> controller.confirm("missing", new ConfirmRequest(3)))
        .satisfies(error -> assertThat(status(error)).isEqualTo(HttpStatus.NOT_FOUND));
  }

  private static Object status(Throwable error) {
    return ((ResponseStatusException) error).getStatusCode();
  }

  private static AuthenticatedUser caller(Long tenantId, Set<String> roles) {
    return new AuthenticatedUser("operator-1", "operator", roles, "", tenantId, Set.of());
  }
}
