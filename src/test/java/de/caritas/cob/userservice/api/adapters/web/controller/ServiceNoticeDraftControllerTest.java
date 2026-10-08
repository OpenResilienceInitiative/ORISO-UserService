package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeDraftService;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeDraftService.DraftInput;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ServiceNoticeDraftControllerTest {
  private final ServiceNoticeDraftService drafts = mock(ServiceNoticeDraftService.class);
  private final DraftInput input =
      new DraftInput(
          LocalDate.of(2026, 10, 2),
          LocalTime.of(14, 0),
          LocalTime.of(15, 0),
          "https://status.operator.dev/maintenance");

  @Test
  void draftAndPreviewRequireTheRealPlatformAdminRolePairOnTenantZero() {
    var rolePair = Set.of(UserRole.AGENCY_ADMIN.getValue(), UserRole.TENANT_ADMIN.getValue());
    for (AuthenticatedUser caller :
        new AuthenticatedUser[] {
          caller(0L, Set.of(UserRole.TENANT_ADMIN.getValue())),
          caller(7L, rolePair),
          caller(0L, Set.of(UserRole.SINGLE_TENANT_ADMIN.getValue())),
          caller(0L, Set.of())
        }) {
      var controller = new ServiceNoticeDraftController(caller, drafts);
      assertThatThrownBy(() -> controller.save("maintenance-1", input))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(
              error ->
                  assertThat(((ResponseStatusException) error).getStatusCode())
                      .isEqualTo(HttpStatus.FORBIDDEN));
      assertThatThrownBy(() -> controller.preview("maintenance-1", "de-sie"))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(
              error ->
                  assertThat(((ResponseStatusException) error).getStatusCode())
                      .isEqualTo(HttpStatus.FORBIDDEN));
    }
    verifyNoInteractions(drafts);
  }

  @Test
  void platformAdminMayReadItsDraftAndPreviewWithoutCreatingARecipientOrSend() {
    var caller =
        caller(0L, Set.of(UserRole.AGENCY_ADMIN.getValue(), UserRole.TENANT_ADMIN.getValue()));
    var controller = new ServiceNoticeDraftController(caller, drafts);
    when(drafts.get("maintenance-1"))
        .thenReturn(
            new ServiceNoticeDraftService.DraftView(
                "maintenance-1",
                "DRAFT",
                input.maintenanceDate(),
                input.maintenanceStart(),
                input.maintenanceEnd(),
                input.statusUrl()));
    assertThat(controller.get("maintenance-1").getBody().status()).isEqualTo("DRAFT");
    assertThat(controller.get("maintenance-1").getHeaders().getCacheControl()).contains("no-store");
  }

  private static AuthenticatedUser caller(Long tenantId, Set<String> roles) {
    return new AuthenticatedUser("operator-1", "operator", roles, "", tenantId, Set.of());
  }
}
