package de.caritas.cob.userservice.api.admin.report.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.agencyadminserivce.generated.ApiClient;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.AdminAgencyControllerApi;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.model.AgencyAdminSearchResultDTO;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyAdminService;
import de.caritas.cob.userservice.api.config.apiclient.AgencyAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class AgencyAdminServiceTest {

  @InjectMocks private AgencyAdminService agencyAdminService;
  @Mock private SecurityHeaderSupplier securityHeaderSupplier;

  @Mock private TenantHeaderSupplier tenantHeaderSupplier;

  @Mock private RestTemplate restTemplate;

  @Mock private AgencyAdminServiceApiControllerFactory agencyAdminServiceApiControllerFactory;

  @Test
  void agencyAdminControllerShouldHaveCorrectHeaders() {
    var headers = new HttpHeaders();
    headers.add("header1", "header1");
    when(securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders()).thenReturn(headers);
    AdminAgencyControllerApi api = new AdminAgencyControllerApi();
    api.setApiClient(new ApiClient());
    when(agencyAdminServiceApiControllerFactory.createControllerApi()).thenReturn(api);

    AdminAgencyControllerApi controllerApi =
        this.agencyAdminServiceApiControllerFactory.createControllerApi();
    ApiClient apiClient = controllerApi.getApiClient();
    agencyAdminService.addDefaultHeaders(apiClient);

    HttpHeaders defaultHeaders =
        (HttpHeaders) ReflectionTestUtils.getField(apiClient, "defaultHeaders");
    assertEquals("header1", defaultHeaders.get("header1").get(0));
  }

  @Test
  void countActiveAgenciesOfTenant_Should_askForTheTenantsUndeletedAgenciesAndReturnTheTotal() {
    when(securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders()).thenReturn(new HttpHeaders());
    var api = mock(AdminAgencyControllerApi.class);
    when(api.getApiClient()).thenReturn(new ApiClient());
    when(agencyAdminServiceApiControllerFactory.createControllerApi()).thenReturn(api);
    when(api.searchAgencies(1, 1, null, null, true, 121L))
        .thenReturn(new AgencyAdminSearchResultDTO().total(3));

    assertEquals(3L, agencyAdminService.countActiveAgenciesOfTenant(121L));
  }

  @Test
  void countActiveAgenciesOfTenant_Should_fail_When_AgencyServiceSendsNoTotal() {
    when(securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders()).thenReturn(new HttpHeaders());
    var api = mock(AdminAgencyControllerApi.class);
    when(api.getApiClient()).thenReturn(new ApiClient());
    when(agencyAdminServiceApiControllerFactory.createControllerApi()).thenReturn(api);
    when(api.searchAgencies(1, 1, null, null, true, 121L))
        .thenReturn(new AgencyAdminSearchResultDTO());

    assertThrows(
        IllegalStateException.class, () -> agencyAdminService.countActiveAgenciesOfTenant(121L));
  }
}
