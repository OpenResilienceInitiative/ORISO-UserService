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
import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.httpheader.HttpHeadersResolver;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.api.service.identity.TechnicalIdentityTokenProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
  private TechnicalIdentityTokenProvider tokenProvider;
  private SimpleMeterRegistry meterRegistry;
  private AgencyMatrixCredentialClient agencyMatrixCredentialClient;

  @BeforeEach
  void setUp() {
    restTemplate = new RestTemplate();
    mockServer = MockRestServiceServer.bindTo(restTemplate).build();
    tokenProvider = mock(TechnicalIdentityTokenProvider.class);
    meterRegistry = new SimpleMeterRegistry();

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
            tokenProvider,
            new OutboundHttpMetrics(meterRegistry));
    ReflectionTestUtils.setField(
        agencyMatrixCredentialClient, "agencyServiceBaseUrl", AGENCY_SERVICE_URL);
  }

  @Test
  void fetchMatrixCredentialsShouldAuthenticateAsTechnicalUser() throws Exception {
    when(tokenProvider.getAccessToken()).thenReturn("technical-access-token");

    var credentials = new AgencyMatrixCredentialsDTO();
    credentials.setMatrixUserId("@agency:matrix");
    credentials.setMatrixPassword("matrix-password");

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
    org.mockito.Mockito.verify(tokenProvider).getAccessToken();
  }

  @Test
  void fetchMatrixCredentialsShouldReturnEmptyWhenAgencyIdIsNull() {
    assertThat(agencyMatrixCredentialClient.fetchMatrixCredentials(null)).isEmpty();
  }

  @Test
  void fetchMatrixCredentialsShouldReturnEmptyWhenTechnicalUserLoginFails() {
    when(tokenProvider.getAccessToken())
        .thenThrow(new IllegalStateException("Keycloak unavailable"));

    assertThat(agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID)).isEmpty();
    mockServer.verify();
  }

  @Test
  void fetchMatrixCredentialsShouldReturnEmptyWhenAgencyHasNoMatrixCredentials() {
    when(tokenProvider.getAccessToken()).thenReturn("technical-access-token");

    mockServer
        .expect(requestTo(AGENCY_SERVICE_URL + "/internal/agencies/42/matrix-service-account"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThat(agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID)).isEmpty();
    mockServer.verify();
  }

  @Test
  void fetchMatrixCredentialsShouldReturnEmptyWhenAgencyServiceFails() {
    when(tokenProvider.getAccessToken()).thenReturn("technical-access-token");

    mockServer
        .expect(requestTo(AGENCY_SERVICE_URL + "/internal/agencies/42/matrix-service-account"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

    assertThat(agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID)).isEmpty();
    mockServer.verify();
  }

  @Test
  void unauthorizedCachedGrantIsInvalidatedAndRetriedExactlyOnce() throws Exception {
    when(tokenProvider.getAccessToken()).thenReturn("stale-token", "fresh-token");

    var credentials = new AgencyMatrixCredentialsDTO();
    credentials.setMatrixUserId("@agency:matrix");
    credentials.setMatrixPassword("matrix-password");
    mockServer
        .expect(requestTo(AGENCY_SERVICE_URL + "/internal/agencies/42/matrix-service-account"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer stale-token"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
    mockServer
        .expect(requestTo(AGENCY_SERVICE_URL + "/internal/agencies/42/matrix-service-account"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fresh-token"))
        .andRespond(
            withSuccess(
                new ObjectMapper().writeValueAsString(credentials), MediaType.APPLICATION_JSON));

    assertThat(agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID))
        .contains(credentials);
    mockServer.verify();
    org.mockito.Mockito.verify(tokenProvider).invalidate("stale-token");
    assertThat(
            meterRegistry
                .get("userservice.outbound.retries")
                .tags(
                    "dependency", "agency-service", "operation", "matrix-credentials-auth-refresh")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void rejectedRefreshedGrantIsInvalidatedWithoutThirdAttempt() {
    when(tokenProvider.getAccessToken()).thenReturn("stale-token", "rejected-fresh-token");

    mockServer
        .expect(requestTo(AGENCY_SERVICE_URL + "/internal/agencies/42/matrix-service-account"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer stale-token"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
    mockServer
        .expect(requestTo(AGENCY_SERVICE_URL + "/internal/agencies/42/matrix-service-account"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer rejected-fresh-token"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

    assertThat(agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID)).isEmpty();
    mockServer.verify();
    org.mockito.Mockito.verify(tokenProvider).invalidate("stale-token");
    org.mockito.Mockito.verify(tokenProvider).invalidate("rejected-fresh-token");
    org.mockito.Mockito.verify(tokenProvider, org.mockito.Mockito.times(2)).getAccessToken();
    assertThat(
            meterRegistry
                .get("userservice.outbound.retries")
                .tags(
                    "dependency", "agency-service", "operation", "matrix-credentials-auth-refresh")
                .counter()
                .count())
        .isEqualTo(1);
  }
}
