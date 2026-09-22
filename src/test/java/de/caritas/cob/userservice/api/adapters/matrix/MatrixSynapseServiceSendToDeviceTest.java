package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * P2 feed-update signal (ADR-020), transport level: a content-free Matrix to-device message sent as
 * the technical admin identity.
 */
@ExtendWith(MockitoExtension.class)
class MatrixSynapseServiceSendToDeviceTest {

  private static final String BASE_URL = "https://matrix.example.com";
  private static final String LOGIN_URL = BASE_URL + "/_matrix/client/r0/login";
  private static final String ADMIN_TOKEN = "admin-token";
  private static final String RECIPIENT = "@alice:matrix.example.com";
  private static final String EVENT_TYPE = "org.oriso.feed.updated";

  private MatrixConfig matrixConfig;
  @Mock private RestTemplate restTemplate;
  @Mock private RestTemplate matrixLongPollRestTemplate;
  @Mock private MatrixRoomClient matrixRoomClient;
  @Mock private MatrixMediaClient matrixMediaClient;

  @BeforeEach
  void setUpMatrixConfig() {
    matrixConfig = new MatrixConfig();
    matrixConfig.setApiUrl(BASE_URL);
  }

  private MatrixSynapseService service() {
    return new MatrixSynapseService(
        matrixConfig,
        restTemplate,
        matrixLongPollRestTemplate,
        matrixRoomClient,
        matrixMediaClient);
  }

  private void stubAdminLogin() {
    matrixConfig.setAdminUsername("admin");
    matrixConfig.setAdminPassword("admin-password");
    when(restTemplate.postForEntity(eq(LOGIN_URL), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of("access_token", ADMIN_TOKEN)));
  }

  @Test
  void sendToDeviceMessageShouldPutToAllDevicesOfTheRecipientAsAdmin() {
    stubAdminLogin();
    when(restTemplate.exchange(
            any(URI.class), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of()));
    var uriCaptor = ArgumentCaptor.forClass(URI.class);
    var requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);

    var accepted = service().sendToDeviceMessage(EVENT_TYPE, RECIPIENT, Map.of());

    assertThat(accepted).isTrue();
    verify(restTemplate)
        .exchange(uriCaptor.capture(), eq(HttpMethod.PUT), requestCaptor.capture(), eq(Map.class));

    var url = uriCaptor.getValue().toString();
    assertThat(url)
        .startsWith(BASE_URL + "/_matrix/client/v3/sendToDevice/org.oriso.feed.updated/");
    // A transaction id must be present and unique per send.
    assertThat(url.substring(url.lastIndexOf('/') + 1)).isNotBlank();

    assertThat(requestCaptor.getValue().getHeaders().getFirst("Authorization"))
        .isEqualTo("Bearer " + ADMIN_TOKEN);

    @SuppressWarnings("unchecked")
    var body = (Map<String, Object>) requestCaptor.getValue().getBody();
    @SuppressWarnings("unchecked")
    var messages = (Map<String, Object>) body.get("messages");
    @SuppressWarnings("unchecked")
    var perDevice = (Map<String, Object>) messages.get(RECIPIENT);
    // "*" = every logged-in device of that user.
    assertThat(perDevice).containsOnlyKeys("*");
    // Privacy: the signal body is empty — no notification content leaves the persisted feed.
    assertThat(perDevice.get("*")).isEqualTo(Map.of());
  }

  @Test
  void sendToDeviceMessageShouldUseAFreshTransactionIdPerSend() {
    stubAdminLogin();
    when(restTemplate.exchange(
            any(URI.class), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(Map.of()));
    var uriCaptor = ArgumentCaptor.forClass(URI.class);
    var service = service();

    service.sendToDeviceMessage(EVENT_TYPE, RECIPIENT, Map.of());
    service.sendToDeviceMessage(EVENT_TYPE, RECIPIENT, Map.of());

    verify(restTemplate, org.mockito.Mockito.times(2))
        .exchange(uriCaptor.capture(), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Map.class));
    assertThat(uriCaptor.getAllValues().get(0)).isNotEqualTo(uriCaptor.getAllValues().get(1));
  }

  @Test
  void sendToDeviceMessageShouldReturnFalseWithoutAdminCredentials() {
    // No admin username/password configured at all.
    var accepted = service().sendToDeviceMessage(EVENT_TYPE, RECIPIENT, Map.of());

    assertThat(accepted).isFalse();
    verify(restTemplate, never())
        .exchange(any(URI.class), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Map.class));
  }

  @Test
  void sendToDeviceMessageShouldSwallowTransportFailures() {
    stubAdminLogin();
    when(restTemplate.exchange(
            any(URI.class), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Map.class)))
        .thenThrow(new org.springframework.web.client.ResourceAccessException("synapse down"));

    var accepted = service().sendToDeviceMessage(EVENT_TYPE, RECIPIENT, Map.of());

    assertThat(accepted).isFalse();
  }

  @Test
  void sendToDeviceMessageShouldRejectBlankRecipient() {
    var accepted = service().sendToDeviceMessage(EVENT_TYPE, "  ", Map.of());

    assertThat(accepted).isFalse();
    verify(restTemplate, never())
        .exchange(any(URI.class), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Map.class));
  }
}
