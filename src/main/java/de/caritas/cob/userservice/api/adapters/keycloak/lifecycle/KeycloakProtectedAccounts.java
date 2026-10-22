package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.PreparedCreation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedCreated;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Actual native owned-read is the only constructor of the disabled pending-account handoff. */
public final class KeycloakProtectedAccounts {
  private final RestTemplate http;
  private final TaskActorConfiguration actors;
  private final TaskActorTokenBroker broker;
  private final ProtectedAccountCodec codec;
  private final URI authServer;
  private final String realmName;
  private final ObjectMapper nodes = new ObjectMapper();

  public KeycloakProtectedAccounts(
      RestTemplate http,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      ProtectedAccountCodec codec,
      String authServer,
      String realmName) {
    this.http = Objects.requireNonNull(http);
    this.actors = Objects.requireNonNull(actors);
    this.broker = Objects.requireNonNull(broker);
    this.codec = Objects.requireNonNull(codec);
    if (realmName == null || realmName.isBlank() || !realmName.equals(codec.realmName()))
      throw invalid();
    this.realmName = realmName;
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

  public VerifiedCreated create(PreparedCreation prepared) {
    outsideSql();
    Objects.requireNonNull(prepared);
    var actor = actors.require(TaskPurpose.ACCOUNT_PROVISIONING);
    if (actor.actor() != TaskActor.ACCOUNT_CREATE) throw invalid();
    byte[] body = codec.createBody(prepared);
    String proof = codec.createProof(prepared, actor);
    byte[] response =
        call(prepared.approvalId(), prepared.attemptId(), null, HttpMethod.PUT, body, proof);
    try {
      return Objects.requireNonNull(codec.verifyCreated(prepared, response));
    } catch (RuntimeException failure) {
      throw new RemoteFailure(0);
    }
  }

  public PendingOwnedAccount ownedRead(AcceptanceClaim claim, VerifiedCreated created) {
    outsideSql();
    if (claim == null
        || created == null
        || claim.frozen() == null
        || !Objects.equals(claim.frozen().approvalId(), created.approvalId())
        || !Objects.equals(claim.frozen().issuerRealm(), created.realmId())
        || !Objects.equals(claim.claimId(), created.claimId())
        || !Objects.equals(claim.attemptId(), created.attemptId())
        || !Objects.equals(claim.executionId(), created.executionId())) throw invalid();
    var actor = actors.require(TaskPurpose.ACCOUNT_PROVISIONING);
    if (actor.actor() != TaskActor.ACCOUNT_CREATE) throw invalid();
    byte[] body = codec.ownedReadBody(created);
    String proof = codec.ownedReadProof(created, actor);
    byte[] response =
        call(created.approvalId(), created.attemptId(), "owned-read", HttpMethod.POST, body, proof);
    try {
      codec.checkOwnedProjection(created, response);
      String username = nodes.readTree(response).path("username").textValue();
      if (username == null || username.isBlank()) throw invalid();
      return new PendingOwnedAccount(claim, created, realmName, username);
    } catch (java.io.IOException | RuntimeException failure) {
      throw new RemoteFailure(0);
    }
  }

  private byte[] call(
      UUID approval, UUID attempt, String suffix, HttpMethod method, byte[] body, String proof) {
    var token = broker.issue(TaskPurpose.ACCOUNT_PROVISIONING);
    if (token.actor() != TaskActor.ACCOUNT_CREATE
        || token.accessToken() == null
        || token.accessToken().isBlank()) throw invalid();
    var route =
        UriComponentsBuilder.fromUri(authServer)
            .pathSegment(
                "realms", realmName, "oriso-protected-invitations", "v1", "approvals",
                approval.toString(), "attempts", attempt.toString());
    if (suffix != null) route.pathSegment(suffix);
    var headers = new HttpHeaders();
    headers.setBearerAuth(token.accessToken());
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    headers.set("X-ORISO-Task-Context", proof);
    try {
      var response =
          http.exchange(
              route.build().encode().toUri(), method, new HttpEntity<>(body, headers), byte[].class);
      int status = response.getStatusCode().value();
      if ((status != 200 && !(method == HttpMethod.PUT && status == 201))
          || response.getBody() == null) throw new RemoteFailure(status);
      return response.getBody();
    } catch (RestClientResponseException failure) {
      throw new RemoteFailure(failure.getStatusCode().value());
    } catch (RestClientException failure) {
      throw new RemoteFailure(0);
    }
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Protected account commands require completed local SQL");
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid protected account transport binding");
  }

  /**
   * Not constructible from an account id, creation receipt or unsigned response supplied by a
   * caller.
   */
  public static final class PendingOwnedAccount {
    private final AcceptanceClaim claim;
    private final VerifiedCreated created;
    private final String realmName;
    private final String username;

    private PendingOwnedAccount(
        AcceptanceClaim claim, VerifiedCreated created, String realmName, String username) {
      this.claim = claim;
      this.created = created;
      this.realmName = realmName;
      this.username = username;
    }

    public AcceptanceClaim claim() {
      return claim;
    }

    public VerifiedCreated created() {
      return created;
    }

    public String realmName() {
      return realmName;
    }

    public String realmId() {
      return claim.frozen().issuerRealm();
    }

    public String username() {
      return username;
    }

    @Override
    public String toString() {
      return "ProtectedPendingOwnedAccount[redacted]";
    }
  }

  public static final class RemoteFailure extends RuntimeException {
    private final int status;

    public RemoteFailure(int status) {
      super("Protected account command unavailable (status=" + status + ")");
      this.status = status;
    }

    public int status() {
      return status;
    }
  }
}
