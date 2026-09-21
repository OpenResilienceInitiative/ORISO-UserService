package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.*;

@ExtendWith(MockitoExtension.class)
class MatrixSynapseServiceStrictAvailabilityTest {
  @Mock private RestTemplate rest;
  private MatrixSynapseService service;
  private MatrixConfig config;

  @BeforeEach
  void setup() {
    config = new MatrixConfig();
    config.setAvailabilityAdminAccessToken("test-token");
    config.setApiUrl("https://matrix.example.com");
    config.setServerName("matrix.example.com");
    service =
        new MatrixSynapseService(
            config, rest, rest, null, null, MatrixIdentifierRedactor.withKey(null));
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 204, 299})
  void confirmedSuccessMeansOccupied(int status) {
    when(rest.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
        .thenReturn(ResponseEntity.status(status).build());
    assertThat(service.userExistsStrict("Otter")).isTrue();
    var url = ArgumentCaptor.forClass(URI.class);
    verify(rest)
        .exchange(url.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    assertThat(url.getValue().getPath()).endsWith("/@otter:matrix.example.com");
  }

  @Test
  void confirmed404MeansAbsent() {
    when(rest.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.NOT_FOUND,
                "missing",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8));
    assertThat(service.userExistsStrict("otter")).isFalse();
    assertThat(service.userExists("otter")).isFalse();
  }

  @Test
  void raw404ResponseMeansAbsent() {
    when(rest.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
        .thenReturn(ResponseEntity.notFound().build());
    assertThat(service.userExistsStrict("otter")).isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {301, 400, 401, 403, 429, 500, 503})
  void unexpectedStatusIsUnavailableButLegacyStillReturnsFalse(int status) {
    when(rest.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
        .thenReturn(ResponseEntity.status(status).build());
    assertThatThrownBy(() -> service.userExistsStrict("otter"))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(service.userExists("otter")).isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 429, 500, 503})
  void httpExceptionIsUnavailable(int status) {
    when(rest.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
        .thenThrow(new HttpClientErrorException(HttpStatusCode.valueOf(status)));
    assertThatThrownBy(() -> service.userExistsStrict("otter"))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void networkFailureIsUnavailableButLegacyStillReturnsFalse() {
    when(rest.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
        .thenThrow(new ResourceAccessException("network failed"));
    assertThatThrownBy(() -> service.userExistsStrict("otter"))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(service.userExists("otter")).isFalse();
  }

  @Test
  void missingTokenIsUnavailableButLegacyStillReturnsFalse() {
    config.setAvailabilityAdminAccessToken(null);
    assertThatThrownBy(() -> service.userExistsStrict("otter"))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(service.userExists("otter")).isFalse();
    verifyNoInteractions(rest);
  }
}
