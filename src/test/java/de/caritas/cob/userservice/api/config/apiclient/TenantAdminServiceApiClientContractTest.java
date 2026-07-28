package de.caritas.cob.userservice.api.config.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.tenantadminservice.generated.web.TenantControllerApi;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class TenantAdminServiceApiClientContractTest {

  @Test
  void shouldKeepProviderLocalConfirmationTimestampsAsWireStrings() {
    var restTemplate = new RestTemplate();
    var server = MockRestServiceServer.bindTo(restTemplate).build();
    var apiClient =
        new TenantAdminServiceApiClient(restTemplate).setBasePath("https://tenant-service.test");
    var controller = new TenantControllerApi(apiClient);
    var providerResponse =
        """
        {
          "id": 42,
          "name": "Tenant",
          "content": {
            "impressum": "Legal",
            "termsAndConditionsConfirmation": "2026-07-27T12:34:56",
            "dataPrivacyConfirmation": "2026-07-27T12:34:56"
          }
        }
        """;
    server
        .expect(requestTo("https://tenant-service.test/tenant/42"))
        .andRespond(withSuccess(providerResponse, MediaType.APPLICATION_JSON));

    var tenant = controller.getTenantById(42L);

    assertThat((Object) tenant.getContent().getTermsAndConditionsConfirmation())
        .isInstanceOf(String.class)
        .isEqualTo("2026-07-27T12:34:56");
    assertThat((Object) tenant.getContent().getDataPrivacyConfirmation())
        .isInstanceOf(String.class)
        .isEqualTo("2026-07-27T12:34:56");
    server.verify();
  }
}
