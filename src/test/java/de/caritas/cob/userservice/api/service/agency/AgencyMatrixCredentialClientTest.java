package de.caritas.cob.userservice.api.service.agency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.httpheader.HttpHeadersResolver;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class AgencyMatrixCredentialClientTest {

  private static final Long AGENCY_ID = 42L;
  private static final String AGENCY_SERVICE_URL = "https://agency.example/service";

  private RestTemplate restTemplate;
  private MockRestServiceServer mockServer;
  private IdentityClient identityClient;
  private IdentityClientConfig identityClientConfig;
  private AgencyMatrixCredentialClient agencyMatrixCredentialClient;

  @BeforeEach
  void setUp() {
    restTemplate = new RestTemplate();
    mockServer = MockRestServiceServer.bindTo(restTemplate).build();
    identityClient = mock(IdentityClient.class);
    identityClientConfig = mock(IdentityClientConfig.class);

    var securityHeaderSupplier = new SecurityHeaderSupplier(new AuthenticatedUser());
    ReflectionTestUtils.setField(securityHeaderSupplier, "csrfHeaderProperty", "csrfHeader");
    ReflectionTestUtils.setField(securityHeaderSupplier, "csrfCookieProperty", "csrfCookie");

    var tenantHeaderSupplier = new TenantHeaderSupplier(new HttpHeadersResolver());
    ReflectionTestUtils.setField(tenantHeaderSupplier, "multitenancy", false);

    agencyMatrixCredentialClient =
        new AgencyMatrixCredentialClient(
            restTemplate,
            securityHeaderSupplier,
            tenantHeaderSupplier,
            identityClient,
            identityClientConfig);
    ReflectionTestUtils.setField(
        agencyMatrixCredentialClient, "agencyServiceBaseUrl", AGENCY_SERVICE_URL);
  }

  @Test
  void fetchMatrixCredentialsShouldAuthenticateAsTechnicalUser() throws Exception {
    var technicalUser = new TechnicalUserConfig();
    technicalUser.setUsername("technical");
    technicalUser.setPassword("secret");

    var loginResponse = new KeycloakLoginResponseDTO();
    loginResponse.setAccessToken("technical-access-token");

    var credentials = new AgencyMatrixCredentialsDTO();
    credentials.setMatrixUserId("@agency:matrix");
    credentials.setMatrixPassword("matrix-password");

    when(identityClientConfig.getTechnicalUser()).thenReturn(technicalUser);
    when(identityClient.loginUser("technical", "secret")).thenReturn(loginResponse);

    mockServer
        .expect(requestTo(AGENCY_SERVICE_URL + "/internal/agencies/42/matrix-service-account"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer technical-access-token"))
        .andExpect(header("agencyId", "42"))
        .andRespond(
            withSuccess(
                new ObjectMapper().writeValueAsString(credentials), MediaType.APPLICATION_JSON));

    var result = agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID);

    assertThat(result).contains(credentials);
    mockServer.verify();
  }

  @Test
  void fetchMatrixCredentialsShouldReturnEmptyWhenAgencyIdIsNull() {
    assertThat(agencyMatrixCredentialClient.fetchMatrixCredentials(null)).isEmpty();
  }
}
