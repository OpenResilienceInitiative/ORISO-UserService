package de.caritas.cob.userservice.api.service.accountinvite.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.agencyadminserivce.generated.ApiClient;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.AdminAgencyControllerApi;
import de.caritas.cob.userservice.api.config.apiclient.AgencyAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TechnicalAccessTokenContext;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class AgencyIdAllocationClientTokenTest {

  private final SecurityHeaderSupplier securityHeaderSupplier = mock(SecurityHeaderSupplier.class);
  private final AgencyAdminServiceApiControllerFactory factory =
      mock(AgencyAdminServiceApiControllerFactory.class);
  private final AdminAgencyControllerApi api = mock(AdminAgencyControllerApi.class);
  private final AgencyIdAllocationClient client =
      new AgencyIdAllocationClient(
          securityHeaderSupplier, mock(TenantHeaderSupplier.class), factory);

  @Test
  void release_Should_SendTheOfferedServiceToken_OnlyOnTheAllocationRequest() {
    when(factory.createControllerApi()).thenReturn(api);
    when(api.getApiClient()).thenReturn(mock(ApiClient.class));
    when(securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders("service-token"))
        .thenReturn(new HttpHeaders());

    TechnicalAccessTokenContext.offerDuring(
        "service-token", () -> client.release(23L, "owner-proof"));

    verify(securityHeaderSupplier).getKeycloakAndCsrfHttpHeaders("service-token");
    verify(securityHeaderSupplier, never()).getKeycloakAndCsrfHttpHeaders();
    assertThat(TechnicalAccessTokenContext.offered()).isEmpty();
    assertThat(TechnicalAccessTokenContext.get()).isEmpty();
  }

  @Test
  void release_Should_UseTheCallersHeaders_When_NoTokenIsOffered() {
    when(factory.createControllerApi()).thenReturn(api);
    when(api.getApiClient()).thenReturn(mock(ApiClient.class));
    when(securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders()).thenReturn(new HttpHeaders());

    client.release(23L, "owner-proof");

    verify(securityHeaderSupplier).getKeycloakAndCsrfHttpHeaders();
    verify(securityHeaderSupplier, never()).getKeycloakAndCsrfHttpHeaders(any(String.class));
  }
}
