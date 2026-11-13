package de.caritas.cob.userservice.api.service.matrixrtc;

import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Takes a person a moderator removed from a group conversation out of the conversation's running
 * call (ORISO-Admin#1518 follow-up).
 *
 * <p>The Matrix ban already stops new call tokens, because the call policy requires room
 * membership. An open LiveKit connection stays, though, so the MatrixRTC gateway is asked to remove
 * the person from the LiveKit room of every call still running in the conversation.
 *
 * <p>Best effort: the ban has happened and must stand, so a failure here is logged and swallowed.
 * The removed person's own app also leaves the call when it sees the ban (ORISO-Frontend).
 */
@Component
@Slf4j
public class CallParticipantEviction {

  private final MatrixCallBindingRepository bindings;
  private final RestTemplate http;
  private final String baseUrl;
  private final String token;

  public CallParticipantEviction(
      MatrixCallBindingRepository bindings,
      RestTemplate http,
      @Value("${matrixrtc.eviction.base-url:}") String baseUrl,
      @Value("${matrixrtc.eviction.token:}") String token) {
    this.bindings = bindings;
    this.http = http;
    this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
    this.token = token == null ? "" : token.trim();
  }

  public void evictFromRunningCalls(String sourceRoomId, String matrixUserId) {
    try {
      List<String> mediaRoomIds = bindings.findRunningMediaRoomIds(sourceRoomId);
      if (mediaRoomIds.isEmpty()) return;
      if (baseUrl.isBlank() || token.length() < 32) {
        log.warn(
            "Call eviction is not configured; a removed participant may stay in a running call");
        return;
      }
      var headers = new HttpHeaders();
      headers.setBearerAuth(token);
      headers.setContentType(MediaType.APPLICATION_JSON);
      var response =
          http.exchange(
              baseUrl + "/internal/matrixrtc/evict",
              HttpMethod.POST,
              new HttpEntity<>(
                  Map.of("matrixUserId", matrixUserId, "mediaRoomIds", mediaRoomIds), headers),
              Void.class);
      if (response.getStatusCode().value() != 204) {
        log.warn("Call eviction was not confirmed (status {})", response.getStatusCode().value());
      }
    } catch (RuntimeException failure) {
      // No identifiers, URLs or bodies in the log.
      log.warn("Call eviction failed ({})", failure.getClass().getSimpleName());
    }
  }
}
