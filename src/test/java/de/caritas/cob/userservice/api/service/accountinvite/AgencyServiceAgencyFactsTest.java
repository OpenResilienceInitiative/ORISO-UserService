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

  @Test
  void find_Should_ReadTheAgency_When_AgencyServiceSendsASettingThisServiceDoesNotKnow() {
    var restTemplate = new org.springframework.web.client.RestTemplate();
    var server =
        org.springframework.test.web.client.MockRestServiceServer.bindTo(restTemplate).build();
    server
        .expect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                "http://agency/agencyadmin/agencies/" + AGENCY_ID))
        .andRespond(
            org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                """
                {"_embedded": {"id": 7, "tenantId": 1, "deleteDate": "null",
                  "settings": {"counsellorTopicPermission": "SOMETHING_NEW"}}}
                """,
                org.springframework.http.MediaType.APPLICATION_JSON));
    var factory = mock(AgencyAdminServiceApiControllerFactory.class);
    when(factory.createControllerApi())
        .thenReturn(
            new AdminAgencyControllerApi(new ApiClient(restTemplate).setBasePath("http://agency")));
    var headers = mock(SecurityHeaderSupplier.class);
    when(headers.getKeycloakAndCsrfHttpHeaders()).thenReturn(new HttpHeaders());
    var facts = new AgencyServiceAgencyFacts(headers, mock(TenantHeaderSupplier.class), factory);

    assertThat(facts.find(AGENCY_ID))
        .hasValueSatisfying(
            agency -> {
              assertThat(agency.tenantId()).isEqualTo(1L);
              assertThat(agency.deleted()).isFalse();
            });
  }
}
