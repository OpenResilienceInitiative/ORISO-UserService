package de.caritas.cob.userservice.api.service.agency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.config.auth.TaskIdentityCredentials;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.httpheader.HttpHeadersResolver;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class AgencyMatrixCredentialClientTest {

  private static final Long AGENCY_ID = 42L;
  private static final String AGENCY_SERVICE_URL = "https://agency.example/service";

  private RestTemplate restTemplate;
  private MockRestServiceServer mockServer;
  private IdentityAuthentication identityAuthentication;
  private IdentityClientConfig identityClientConfig;
  private AgencyMatrixCredentialClient agencyMatrixCredentialClient;

  @BeforeEach
  void setUp() {
    restTemplate = new RestTemplate();
    mockServer = MockRestServiceServer.bindTo(restTemplate).build();
    identityAuthentication = mock(IdentityAuthentication.class);
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
            identityAuthentication,
            identityClientConfig);
    ReflectionTestUtils.setField(
        agencyMatrixCredentialClient, "agencyServiceBaseUrl", AGENCY_SERVICE_URL);
  }

  @Test
  void fetchMatrixCredentialsShouldAuthenticateAsTechnicalUser() throws Exception {
    stubTechnicalUserLogin("technical-access-token");

    var credentials = new AgencyMatrixCredentialsDTO();
    credentials.setMatrixUserId("@agency:matrix");

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

  @Test
  void oldAgencyResponseRemainsCompatibleButPasswordIsDiscarded() throws Exception {
    stubTechnicalUserLogin("technical-access-token");
    mockServer
        .expect(requestTo(AGENCY_SERVICE_URL + "/internal/agencies/42/matrix-service-account"))
        .andRespond(
            withSuccess(
                "{\"matrixUserId\":\"@agency:matrix\",\"matrixPassword\":\"public-legacy-fixture\"}",
                MediaType.APPLICATION_JSON));
    var identity = agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID).orElseThrow();
    assertThat(identity.getMatrixUserId()).isEqualTo("@agency:matrix");
    assertThat(new ObjectMapper().writeValueAsString(identity))
        .isEqualTo("{\"matrixUserId\":\"@agency:matrix\"}");
    assertThat(identity.toString()).doesNotContain("public-legacy-fixture", "Password");
    mockServer.verify();
  }

  @Test
  void fetchMatrixCredentialsShouldReturnEmptyWhenTechnicalUserLoginFails() {
    var technicalUser = new TaskIdentityCredentials();
    technicalUser.setClientId("technical");
    technicalUser.setClientSecret("secret");

    when(identityClientConfig.getTaskIdentity(org.mockito.ArgumentMatchers.any()))
        .thenReturn(technicalUser);
    when(identityAuthentication.loginTask(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new BadRequestException("Keycloak unavailable"));

    assertThat(agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID)).isEmpty();
    mockServer.verify();
  }

  @Test
  void fetchMatrixCredentialsShouldReturnEmptyWhenAgencyHasNoMatrixCredentials() {
    stubTechnicalUserLogin("technical-access-token");

    mockServer
        .expect(requestTo(AGENCY_SERVICE_URL + "/internal/agencies/42/matrix-service-account"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThat(agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID)).isEmpty();
    mockServer.verify();
  }

  @Test
  void fetchMatrixCredentialsShouldReturnEmptyWhenAgencyServiceFails() {
    stubTechnicalUserLogin("technical-access-token");

    mockServer
        .expect(requestTo(AGENCY_SERVICE_URL + "/internal/agencies/42/matrix-service-account"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

    assertThat(agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID)).isEmpty();
    mockServer.verify();
  }

  private void stubTechnicalUserLogin(String accessToken) {
    var technicalUser = new TaskIdentityCredentials();
    technicalUser.setClientId("technical");
    technicalUser.setClientSecret("secret");

    var loginResponse = new IdentityLogin(accessToken, 0, 0, "refresh-token");

    when(identityClientConfig.getTaskIdentity(org.mockito.ArgumentMatchers.any()))
        .thenReturn(technicalUser);
    when(identityAuthentication.loginTask(org.mockito.ArgumentMatchers.any()))
        .thenReturn(loginResponse);
  }
}
