package de.caritas.cob.userservice.api.adapters.matrix;

import static org.mockito.Mockito.mock;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.config.observability.LiveChatDiagnosticMetrics;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * The {@link FakeSynapseHomeserver} plus the one thing it does not answer yet: {@code POST
 * /rooms/{roomId}/leave}, which is how {@link MatrixRoomClient#leaveRoom} leaves a room with the
 * leaving user's own token. Everything else, including every rule on who may invite, join or change
 * power levels, is the homeserver's own; a user who is not in the room is refused with 403, like
 * Synapse does.
 */
public final class FakeSynapseWithSelfLeave extends RestTemplate {

  private static final String TOKEN_PREFIX = "Bearer token-for:";

  private final FakeSynapseHomeserver homeserver;

  public FakeSynapseWithSelfLeave(FakeSynapseHomeserver homeserver) {
    this.homeserver = homeserver;
  }

  /** The production gateway, talking to the homeserver through this wrapper. */
  public MatrixSynapseService gateway() {
    var config = new MatrixConfig();
    config.setApiUrl("https://" + FakeSynapseHomeserver.SERVER_NAME);
    config.setServerName(FakeSynapseHomeserver.SERVER_NAME);
    config.setAdminUsername(FakeSynapseHomeserver.ADMIN_LOCALPART);
    config.setAdminPassword("admin-secret");
    config.setRegistrationSharedSecret("fake-registration-secret");
    var roomClient = new MatrixRoomClient(config, this, mock(LiveChatDiagnosticMetrics.class));
    return new MatrixSynapseService(
        config,
        this,
        this,
        roomClient,
        mock(MatrixMediaClient.class),
        MatrixIdentifierRedactor.withKey("fake-synapse"));
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> ResponseEntity<T> postForEntity(
      String url, Object request, Class<T> responseType, Object... uriVariables) {
    return homeserver.postForEntity(url, request, responseType, uriVariables);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> ResponseEntity<T> postForEntity(URI url, Object request, Class<T> responseType) {
    if (url.getRawPath().endsWith("/leave") && url.getRawPath().contains("/rooms/")) {
      return (ResponseEntity<T>) selfLeave(url, request);
    }
    return homeserver.postForEntity(url, request, responseType);
  }

  @Override
  public <T> ResponseEntity<T> exchange(
      URI url, HttpMethod method, HttpEntity<?> requestEntity, Class<T> responseType) {
    return homeserver.exchange(url, method, requestEntity, responseType);
  }

  @Override
  public void put(URI url, Object request) {
    homeserver.put(url, request);
  }

  private ResponseEntity<?> selfLeave(URI url, Object request) {
    String[] path = url.getRawPath().split("/");
    String roomId = java.net.URLDecoder.decode(path[path.length - 2], StandardCharsets.UTF_8);
    String header =
        request instanceof HttpEntity<?> entity
            ? entity.getHeaders().getFirst("Authorization")
            : null;
    if (header == null || !header.startsWith(TOKEN_PREFIX)) {
      throw refused(HttpStatus.UNAUTHORIZED, "M_MISSING_TOKEN");
    }
    String user = header.substring(TOKEN_PREFIX.length());
    if (!homeserver.isMember(roomId, user)) {
      throw refused(HttpStatus.FORBIDDEN, "M_FORBIDDEN");
    }
    homeserver.memberLeaves(roomId, user);
    return ResponseEntity.ok(Map.of());
  }

  private static HttpClientErrorException refused(HttpStatus status, String errcode) {
    return HttpClientErrorException.create(
        status,
        status.getReasonPhrase(),
        null,
        ("{\"errcode\":\"" + errcode + "\"}").getBytes(StandardCharsets.UTF_8),
        StandardCharsets.UTF_8);
  }
}
