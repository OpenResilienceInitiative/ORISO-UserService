package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.agencyadminserivce.generated.ApiClient;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.AdminAgencyControllerApi;
import de.caritas.cob.userservice.api.config.apiclient.AgencyAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

class AgencyServiceAgencyFactsTest {

  private static final long AGENCY_ID = 7L;

  private final AdminAgencyControllerApi api = mock(AdminAgencyControllerApi.class);
  private AgencyServiceAgencyFacts client;

  @BeforeEach
  void setUp() {
    var headers = mock(SecurityHeaderSupplier.class);
    when(headers.getKeycloakAndCsrfHttpHeaders()).thenReturn(new HttpHeaders());
    var factory = mock(AgencyAdminServiceApiControllerFactory.class);
    when(factory.createControllerApi()).thenReturn(api);
    when(api.getApiClient()).thenReturn(mock(ApiClient.class));
    client = new AgencyServiceAgencyFacts(headers, mock(TenantHeaderSupplier.class), factory);
  }

  @ParameterizedTest
  @EnumSource(
      value = HttpStatus.class,
      names = {"NOT_FOUND", "FORBIDDEN"})
  void find_Should_ReturnEmpty_When_AgencyServiceHidesTheAgency(HttpStatus status) {
    when(api.getAgency(AGENCY_ID))
        .thenThrow(HttpClientErrorException.create(status, "", null, null, null));

    assertThat(client.find(AGENCY_ID)).isEmpty();
  }

  @Test
  void find_Should_ReturnEmpty_When_AgencyServiceAnswersWithoutAnAgency() {
    assertThat(client.find(AGENCY_ID)).isEmpty();
  }
}
