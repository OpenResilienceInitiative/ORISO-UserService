package de.caritas.cob.userservice.api.admin.service.tenant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.tenantadminservice.generated.ApiClient;
import de.caritas.cob.userservice.tenantadminservice.generated.web.TenantAdminControllerApi;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class TenantAdminServiceTest {

  @Test
  void getTenantByIdForwardsCurrentTenantContextToDownstreamService() {
    var securityHeaderSupplier = mock(SecurityHeaderSupplier.class);
    var controllerFactory = mock(TenantAdminServiceApiControllerFactory.class);
    var controller = mock(TenantAdminControllerApi.class);
    var apiClient = mock(ApiClient.class);
    when(securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders()).thenReturn(new HttpHeaders());
    when(controllerFactory.createControllerApi()).thenReturn(controller);
    when(controller.getApiClient()).thenReturn(apiClient);
    when(controller.getTenantById(20L)).thenReturn(new TenantDTO());
    TenantContext.setCurrentTenant(20L);

    try {
      new TenantAdminService(securityHeaderSupplier, controllerFactory).getTenantById(20L);

      verify(apiClient).addDefaultHeader("tenantId", "20");
    } finally {
      TenantContext.clear();
    }
  }
}
