package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.FrozenApproval;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.VerifiedApproval;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Human approval is immediate; background recovery never stores or substitutes a human bearer. */
public final class KeycloakProtectedInvitations {
  private final RestTemplate http;
  private final TaskActorConfiguration actors;
  private final TaskActorTokenBroker broker;
  private final ProtectedInvitationCodec codec;
  private final URI authServer;

  public KeycloakProtectedInvitations(
      RestTemplate http,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      ProtectedInvitationCodec codec,
      String authServer,
      String realmName) {
    this.http = Objects.requireNonNull(http);
    this.actors = Objects.requireNonNull(actors);
    this.broker = Objects.requireNonNull(broker);
    this.codec = Objects.requireNonNull(codec);
    if (!codec.realmName().equals(realmName)) throw invalid();
    try {
      URI server = URI.create(authServer);
      if (!("http".equalsIgnoreCase(server.getScheme())
              || "https".equalsIgnoreCase(server.getScheme()))
          || server.getHost() == null
          || server.getRawUserInfo() != null
          || server.getRawQuery() != null
          || server.getRawFragment() != null) throw invalid();
      this.authServer = server;
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  public VerifiedApproval approve(FrozenApproval frozen, String currentHumanBearer) {
    requireCompletedLocalTransaction();
    String body = codec.approvalBody(frozen);
    HttpHeaders headers = headers(currentHumanBearer);
    return call(frozen, HttpMethod.PUT, "", body, headers);
  }

  public VerifiedApproval reconcile(FrozenApproval frozen) {
    requireCompletedLocalTransaction();
    String body = codec.statusBody(frozen);
    var actor = actors.require(TaskPurpose.INVITE_RESERVATIONS);
    String proof = codec.statusProof(frozen, actor);
    var token = broker.issue(TaskPurpose.INVITE_RESERVATIONS);
    HttpHeaders headers = headers(token.accessToken());
    headers.set("X-ORISO-Task-Context", proof);
    return call(frozen, HttpMethod.POST, "status", body, headers);
  }

  private VerifiedApproval call(
      FrozenApproval frozen, HttpMethod method, String suffix, String body, HttpHeaders headers) {
    var uri =
        UriComponentsBuilder.fromUri(authServer)
            .pathSegment(
                "realms",
                codec.realmName(),
                "oriso-protected-invitations",
                "v1",
                "approvals",
                frozen.approvalId().toString());
    if (!suffix.isEmpty()) uri.pathSegment(suffix);
    try {
      var response =
          http.exchange(
              uri.build().encode().toUri(),
              method,
              new HttpEntity<>(body.getBytes(StandardCharsets.UTF_8), headers),
              byte[].class);
      int status = response.getStatusCode().value();
      if (status != 200 && !(method == HttpMethod.PUT && status == 201)) throw new RemoteFailure(0);
      return codec.verifyAuthorized(frozen, response.getBody());
    } catch (RestClientResponseException failure) {
      throw new RemoteFailure(failure.getStatusCode().value());
    } catch (RestClientException | IllegalArgumentException failure) {
      throw new RemoteFailure(0);
    }
  }

  private static HttpHeaders headers(String bearer) {
    if (bearer == null
        || bearer.isBlank()
        || bearer.length() > 65536
        || bearer
            .codePoints()
            .anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) throw invalid();
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    headers.setCacheControl("no-store");
    headers.setBearerAuth(bearer);
    return headers;
  }

  private static void requireCompletedLocalTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Receiver commands require a completed local transaction");
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid protected invitation transport binding");
  }

  public static final class RemoteFailure extends RuntimeException {
    private final int status;

    public RemoteFailure(int status) {
      super("Protected invitation command unavailable (status=" + status + ")");
      this.status = status;
    }

    public int status() {
      return status;
    }
  }
}
