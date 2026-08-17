package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.identity.TechnicalIdentityTokenProvider;
import de.caritas.cob.userservice.tenantadminservice.generated.ApiClient;
import de.caritas.cob.userservice.tenantadminservice.generated.web.TenantControllerApi;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.MultilingualTenantDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

@ExtendWith(MockitoExtension.class)
class TenantCreationClientTest {

  @Mock private SecurityHeaderSupplier securityHeaderSupplier;
  @Mock private TechnicalIdentityTokenProvider technicalIdentityTokenProvider;
  @Mock private TenantAdminServiceApiControllerFactory controllerFactory;
  @Mock private TenantControllerApi tenantControllerApi;
  @Mock private ApiClient apiClient;

  private TenantCreationClient client;

  @BeforeEach
  void setUp() {
    when(technicalIdentityTokenProvider.getAccessToken()).thenReturn("technical-token");
    when(securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders("technical-token"))
        .thenReturn(new HttpHeaders());
    when(controllerFactory.createControllerApi()).thenReturn(tenantControllerApi);
    when(tenantControllerApi.getApiClient()).thenReturn(apiClient);
    client =
        new TenantCreationClient(
            securityHeaderSupplier, technicalIdentityTokenProvider, controllerFactory);
  }

  @Test
  void createTenantUsesTheSharedTechnicalIdentityGrant() {
    var request = new MultilingualTenantDTO();
    var response = new MultilingualTenantDTO();
    when(tenantControllerApi.createTenant(request)).thenReturn(response);

    assertSame(response, client.createTenant(request));

    verify(technicalIdentityTokenProvider).getAccessToken();
    verify(securityHeaderSupplier).getKeycloakAndCsrfHttpHeaders("technical-token");
  }
}
