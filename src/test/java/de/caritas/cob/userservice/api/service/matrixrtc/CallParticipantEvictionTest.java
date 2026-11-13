package de.caritas.cob.userservice.api.service.matrixrtc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * ORISO-Admin#1518 follow-up: a person a moderator removes from a Gesprächskreis is taken out of
 * the conversation's running call, not only out of its Matrix room.
 */
class CallParticipantEvictionTest {

  private static final String TOKEN = "t".repeat(40);
  private static final String SOURCE_ROOM = "!circle:oriso.test";
  private static final String ANNA = "@anna:oriso.test";

  private final MatrixCallBindingRepository bindings = mock(MatrixCallBindingRepository.class);
  private final RestTemplate http = mock(RestTemplate.class);

  private CallParticipantEviction eviction(String baseUrl, String token) {
    return new CallParticipantEviction(bindings, http, baseUrl, token);
  }

  @Test
  @SuppressWarnings("unchecked")
  void asksTheGatewayToRemoveThePersonFromEveryRunningCallOfTheConversation() {
    when(bindings.findRunningMediaRoomIds(SOURCE_ROOM)).thenReturn(List.of("!media:oriso.test"));
    when(http.exchange(anyString(), any(), any(), eq(Void.class)))
        .thenReturn(ResponseEntity.noContent().build());

    eviction("http://gateway:3010/", TOKEN).evictFromRunningCalls(SOURCE_ROOM, ANNA);

    ArgumentCaptor<HttpEntity<Map<String, Object>>> request =
        ArgumentCaptor.forClass(HttpEntity.class);
    verify(http)
        .exchange(
            eq("http://gateway:3010/internal/matrixrtc/evict"),
            eq(HttpMethod.POST),
            request.capture(),
            eq(Void.class));
    assertThat(request.getValue().getHeaders().getFirst("Authorization"))
        .isEqualTo("Bearer " + TOKEN);
    assertThat(request.getValue().getBody())
        .isEqualTo(Map.of("matrixUserId", ANNA, "mediaRoomIds", List.of("!media:oriso.test")));
  }

  @Test
  void doesNothingWithoutARunningCall() {
    when(bindings.findRunningMediaRoomIds(SOURCE_ROOM)).thenReturn(List.of());

    eviction("http://gateway:3010", TOKEN).evictFromRunningCalls(SOURCE_ROOM, ANNA);

    verify(http, never()).exchange(anyString(), any(), any(), eq(Void.class));
  }

  @Test
  void staysOffWhileTheGatewayIsNotConfigured() {
    when(bindings.findRunningMediaRoomIds(SOURCE_ROOM)).thenReturn(List.of("!media:oriso.test"));

    eviction("", TOKEN).evictFromRunningCalls(SOURCE_ROOM, ANNA);
    eviction("http://gateway:3010", "short").evictFromRunningCalls(SOURCE_ROOM, ANNA);

    verify(http, never()).exchange(anyString(), any(), any(), eq(Void.class));
  }

  @Test
  void neverUndoesTheBanWhenTheGatewayFails() {
    when(bindings.findRunningMediaRoomIds(SOURCE_ROOM)).thenReturn(List.of("!media:oriso.test"));
    when(http.exchange(anyString(), any(), any(), eq(Void.class)))
        .thenThrow(new ResourceAccessException("down"));

    assertThatCode(
            () -> eviction("http://gateway:3010", TOKEN).evictFromRunningCalls(SOURCE_ROOM, ANNA))
        .doesNotThrowAnyException();
  }

  @Test
  void neverUndoesTheBanWhenTheCallLookupFails() {
    when(bindings.findRunningMediaRoomIds(SOURCE_ROOM)).thenThrow(new RuntimeException("db"));

    assertThatCode(
            () -> eviction("http://gateway:3010", TOKEN).evictFromRunningCalls(SOURCE_ROOM, ANNA))
        .doesNotThrowAnyException();
  }
}
