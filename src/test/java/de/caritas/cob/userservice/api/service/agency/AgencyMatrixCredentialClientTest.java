package de.caritas.cob.userservice.api.service.agency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class AgencyMatrixCredentialClientTest {

  private static final String AGENCY_SERVICE_BASE_URL = "http://agency-service";
  private static final Long AGENCY_ID = 42L;
  private static final String MATRIX_USER_ID = "@agency:matrix.example.com";
  private static final String MATRIX_PASSWORD = "matrix-password";

  @InjectMocks private AgencyMatrixCredentialClient agencyMatrixCredentialClient;

  @Mock private RestTemplate restTemplate;
  @Mock private SecurityHeaderSupplier securityHeaderSupplier;
  @Mock private TenantHeaderSupplier tenantHeaderSupplier;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(
        agencyMatrixCredentialClient, "agencyServiceBaseUrl", AGENCY_SERVICE_BASE_URL);
  }

  @Test
  void fetchMatrixCredentials_Should_returnEmpty_When_agencyIdIsNull() {
    Optional<AgencyMatrixCredentialsDTO> result =
        agencyMatrixCredentialClient.fetchMatrixCredentials(null);

    assertThat(result).isEmpty();
    verifyNoInteractions(restTemplate, securityHeaderSupplier, tenantHeaderSupplier);
  }

  @Test
  void fetchMatrixCredentials_Should_returnCredentials_When_requestSucceeds() {
    HttpHeaders headers = new HttpHeaders();
    when(securityHeaderSupplier.getCsrfHttpHeaders()).thenReturn(headers);
    AgencyMatrixCredentialsDTO credentials = credentials();
    when(restTemplate.exchange(
            eq(expectedUrl()),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(AgencyMatrixCredentialsDTO.class)))
        .thenReturn(ResponseEntity.ok(credentials));

    Optional<AgencyMatrixCredentialsDTO> result =
        agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID);

    assertThat(result).contains(credentials);
    verify(tenantHeaderSupplier).addTenantHeader(headers);

    ArgumentCaptor<HttpEntity<?>> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate)
        .exchange(
            eq(expectedUrl()),
            eq(HttpMethod.GET),
            requestCaptor.capture(),
            eq(AgencyMatrixCredentialsDTO.class));
    assertThat(requestCaptor.getValue().getHeaders().getFirst("agencyId"))
        .isEqualTo(String.valueOf(AGENCY_ID));
  }

  @Test
  void fetchMatrixCredentials_Should_returnEmpty_When_responseIsNotSuccessful() {
    when(securityHeaderSupplier.getCsrfHttpHeaders()).thenReturn(new HttpHeaders());
    when(restTemplate.exchange(
            eq(expectedUrl()),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(AgencyMatrixCredentialsDTO.class)))
        .thenReturn(ResponseEntity.status(HttpStatus.NO_CONTENT).build());

    Optional<AgencyMatrixCredentialsDTO> result =
        agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID);

    assertThat(result).isEmpty();
  }

  @Test
  void fetchMatrixCredentials_Should_returnEmpty_When_responseIsErrorWithBody() {
    when(securityHeaderSupplier.getCsrfHttpHeaders()).thenReturn(new HttpHeaders());
    when(restTemplate.exchange(
            eq(expectedUrl()),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(AgencyMatrixCredentialsDTO.class)))
        .thenReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(credentials()));

    Optional<AgencyMatrixCredentialsDTO> result =
        agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID);

    assertThat(result).isEmpty();
  }

  @Test
  void fetchMatrixCredentials_Should_returnEmpty_When_responseBodyIsNull() {
    when(securityHeaderSupplier.getCsrfHttpHeaders()).thenReturn(new HttpHeaders());
    when(restTemplate.exchange(
            eq(expectedUrl()),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(AgencyMatrixCredentialsDTO.class)))
        .thenReturn(ResponseEntity.ok(null));

    Optional<AgencyMatrixCredentialsDTO> result =
        agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID);

    assertThat(result).isEmpty();
  }

  @Test
  void fetchMatrixCredentials_Should_returnEmpty_When_agencyHasNoCredentials() {
    when(securityHeaderSupplier.getCsrfHttpHeaders()).thenReturn(new HttpHeaders());
    when(restTemplate.exchange(
            eq(expectedUrl()),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(AgencyMatrixCredentialsDTO.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.NOT_FOUND,
                "Not Found",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8));

    Optional<AgencyMatrixCredentialsDTO> result =
        agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID);

    assertThat(result).isEmpty();
  }

  @Test
  void fetchMatrixCredentials_Should_returnEmpty_When_requestFails() {
    when(securityHeaderSupplier.getCsrfHttpHeaders()).thenReturn(new HttpHeaders());
    when(restTemplate.exchange(
            eq(expectedUrl()),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(AgencyMatrixCredentialsDTO.class)))
        .thenThrow(new RestClientException("connection failed"));

    Optional<AgencyMatrixCredentialsDTO> result =
        agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID);

    assertThat(result).isEmpty();
  }

  @Test
  void fetchMatrixCredentials_Should_forwardTenantHeader_When_tenantSupplierAddsHeader() {
    HttpHeaders headers = new HttpHeaders();
    when(securityHeaderSupplier.getCsrfHttpHeaders()).thenReturn(headers);
    doAnswer(
            invocation -> {
              headers.add("tenantId", "7");
              return null;
            })
        .when(tenantHeaderSupplier)
        .addTenantHeader(headers);
    when(restTemplate.exchange(
            eq(expectedUrl()),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(AgencyMatrixCredentialsDTO.class)))
        .thenReturn(ResponseEntity.ok(credentials()));

    agencyMatrixCredentialClient.fetchMatrixCredentials(AGENCY_ID);

    ArgumentCaptor<HttpEntity<?>> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate)
        .exchange(
            eq(expectedUrl()),
            eq(HttpMethod.GET),
            requestCaptor.capture(),
            eq(AgencyMatrixCredentialsDTO.class));
    assertThat(requestCaptor.getValue().getHeaders().getFirst("tenantId")).isEqualTo("7");
  }

  private static String expectedUrl() {
    return AGENCY_SERVICE_BASE_URL + "/internal/agencies/" + AGENCY_ID + "/matrix-service-account";
  }

  private static AgencyMatrixCredentialsDTO credentials() {
    AgencyMatrixCredentialsDTO credentials = new AgencyMatrixCredentialsDTO();
    credentials.setMatrixUserId(MATRIX_USER_ID);
    credentials.setMatrixPassword(MATRIX_PASSWORD);
    return credentials;
  }
}
