package de.caritas.cob.userservice.api.service.agency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.agencyserivce.generated.ApiClient;
import de.caritas.cob.userservice.agencyserivce.generated.web.AgencyControllerApi;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * The AgencyService's real JSON shape through the generated client: the three "Direkteinladung
 * erlauben" fields must survive parsing, or the policy would read "not served" and refuse all.
 */
class EffectiveAgencySettingsLookupJsonTest {

  @Test
  void parsesTheThreeDirectInviteFieldsOfTheAgencyServiceResponse() {
    var restTemplate = new RestTemplate();
    var server = MockRestServiceServer.bindTo(restTemplate).build();
    server
        .expect(requestTo("http://agency.test/agencies/42"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                [{"id":42,"name":"Beratungsstelle","tenantId":7,"consultingType":1,
                  "settings":{"featureCallsEnabled":true,
                              "featureDirectInviteChatEnabled":true,
                              "featureDirectInviteAudioEnabled":false,
                              "featureDirectInviteVideoEnabled":true,
                              "someFieldAnOlderClientDoesNotKnow":"x"}}]
                """,
                MediaType.APPLICATION_JSON));
    var factory = mock(AgencyServiceApiControllerFactory.class);
    when(factory.createControllerApi())
        .thenReturn(
            new AgencyControllerApi(new ApiClient(restTemplate).setBasePath("http://agency.test")));
    var security = mock(SecurityHeaderSupplier.class);
    when(security.getOptionalKeycloakAndCsrfHttpHeaders()).thenReturn(new HttpHeaders());
    var lookup =
        new EffectiveAgencySettingsLookup(factory, security, mock(TenantHeaderSupplier.class));

    var settings = lookup.findEffectiveSettings(42L, 7L).orElseThrow();

    assertThat(settings.getFeatureDirectInviteChatEnabled()).isTrue();
    assertThat(settings.getFeatureDirectInviteAudioEnabled()).isFalse();
    assertThat(settings.getFeatureDirectInviteVideoEnabled()).isTrue();
    server.verify();
  }
}
