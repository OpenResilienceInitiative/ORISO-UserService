package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class MatrixSynapseServiceTest {

  private static final String SYNC_URL = "https://matrix.example/_matrix/client/r0/sync";
  private static final String ACCESS_TOKEN = "access-token";

  @Mock private MatrixConfig matrixConfig;
  @Mock private RestTemplate restTemplate;
  @Mock private RestTemplate matrixLongPollRestTemplate;
  @Mock private MatrixRoomClient matrixRoomClient;
  @Mock private MatrixMediaClient matrixMediaClient;

  @Test
  void makeMatrixRequestShouldUseDedicatedLongPollRestTemplate() {
    var responseBody = Map.<String, Object>of("next_batch", "sync-token");
    when(matrixLongPollRestTemplate.exchange(
            eq(SYNC_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(responseBody));
    var service = matrixSynapseService();

    var result = service.makeMatrixRequest(SYNC_URL, "GET", ACCESS_TOKEN, null);

    assertThat(result).isSameAs(responseBody);
    verify(matrixLongPollRestTemplate)
        .exchange(eq(SYNC_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    verifyNoInteractions(restTemplate);
  }

  @Test
  void makeMatrixRequestShouldPassBearerTokenToLongPollRestTemplate() {
    when(matrixLongPollRestTemplate.exchange(
            eq(SYNC_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of()));
    var requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
    var service = matrixSynapseService();

    service.makeMatrixRequest(SYNC_URL, "GET", ACCESS_TOKEN, null);

    verify(matrixLongPollRestTemplate)
        .exchange(eq(SYNC_URL), eq(HttpMethod.GET), requestCaptor.capture(), eq(Map.class));
    assertThat(requestCaptor.getValue().getHeaders().getFirst("Authorization"))
        .isEqualTo("Bearer " + ACCESS_TOKEN);
  }

  private MatrixSynapseService matrixSynapseService() {
    return new MatrixSynapseService(
        matrixConfig,
        restTemplate,
        matrixLongPollRestTemplate,
        matrixRoomClient,
        matrixMediaClient);
  }
}
