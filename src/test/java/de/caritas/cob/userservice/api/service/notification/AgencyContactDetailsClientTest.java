package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class AgencyContactDetailsClientTest {
  @Mock private IdentityAuthentication authentication;
  @Mock private IdentityClientConfig identityConfig;
  @Mock private SecurityHeaderSupplier headers;

  private MockRestServiceServer server;
  private AgencyContactDetailsClient client;

  @BeforeEach
  void setUp() {
    var http = new RestTemplate();
    server = MockRestServiceServer.bindTo(http).build();
    client = new AgencyContactDetailsClient(http, authentication, identityConfig, headers);
    ReflectionTestUtils.setField(client, "agencyServiceUrl", "https://agency.internal/service");
  }

  @Test
  void readsOnlyTheRequestedTenantAndAgencyWithTechnicalIdentity() {
    technicalIdentity();
    server
        .expect(
            once(),
            requestTo(
                "https://agency.internal/service/internal/agencies/9/contact-details?tenantId=7"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer technical-token"))
        .andRespond(
            withSuccess(
                "{\"agencyId\":9,\"tenantId\":7,\"name\":\"Centre\",\"phone\":\"+49 30 123\",\"email\":null,\"openingHours\":null}",
                MediaType.APPLICATION_JSON));

    var details = client.read(9L, 7L);

    assertThat(details.name()).isEqualTo("Centre");
    assertThat(details.phone()).isEqualTo("+49 30 123");
    server.verify();
  }

  @Test
  void rejectsAnUnexpectedTenantInTheResponse() {
    technicalIdentity();
    server
        .expect(
            once(),
            requestTo(
                "https://agency.internal/service/internal/agencies/9/contact-details?tenantId=7"))
        .andRespond(
            withSuccess(
                "{\"agencyId\":9,\"tenantId\":8,\"name\":\"Wrong tenant\"}",
                MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.read(9L, 7L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("do not match");
  }

  @Test
  void missingAgencyServiceUrlFailsStartup() {
    ReflectionTestUtils.setField(client, "agencyServiceUrl", "");

    assertThatThrownBy(client::validateAgencyServiceUrl)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AGENCY_SERVICE_API_URL");
  }

  @Test
  void malformedAgencyServiceUrlFailsStartupWithTheConfigName() {
    ReflectionTestUtils.setField(client, "agencyServiceUrl", "https://bad host");

    assertThatThrownBy(client::validateAgencyServiceUrl)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AGENCY_SERVICE_API_URL");
  }

  private void technicalIdentity() {
    var account = new TechnicalUserConfig();
    account.setUsername("technical");
    account.setPassword("test-secret");
    when(identityConfig.getTechnicalUser()).thenReturn(account);
    when(authentication.login("technical", "test-secret"))
        .thenReturn(new IdentityLogin("technical-token", 60, 60, "refresh"));
    var authorization = new HttpHeaders();
    authorization.setBearerAuth("technical-token");
    when(headers.getKeycloakAndCsrfHttpHeaders("technical-token")).thenReturn(authorization);
  }
}
