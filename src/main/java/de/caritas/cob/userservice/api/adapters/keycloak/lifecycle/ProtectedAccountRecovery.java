package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.LocalOutcome;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.PreparedRecovery;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedCleanup;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedRecovery;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
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

/** Fixed native cleanup RPCs; never a generic account deletion port. */
public final class ProtectedAccountRecovery {
  private final RestTemplate http;
  private final TaskActorConfiguration actors;
  private final TaskActorTokenBroker broker;
  private final ProtectedAccountCodec codec;
  private final URI authServer;
  private final String realmName;

  public ProtectedAccountRecovery(
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

  public VerifiedRecovery recover(PreparedRecovery recovery) {
    outsideSql();
    Objects.requireNonNull(recovery);
    var actor = cleanupActor();
    byte[] body = codec.recoverBody(recovery);
    String proof = codec.recoverProof(recovery, actor);
    byte[] response = call(recovery.approvalId(), recovery.attemptId(), "recover", body, proof);
    try {
      return Objects.requireNonNull(codec.verifyRecovery(recovery, response));
    } catch (RuntimeException failure) {
      throw new KeycloakProtectedAccounts.RemoteFailure(0);
    }
  }

  public VerifiedCleanup compensate(VerifiedRecovery recovery, LocalOutcome local) {
    outsideSql();
    Objects.requireNonNull(recovery);
    Objects.requireNonNull(local);
    var actor = cleanupActor();
    byte[] body = codec.compensateBody(recovery, local);
    String proof = codec.compensateProof(recovery, local, actor);
    byte[] response = call(recovery.approvalId(), recovery.attemptId(), "compensate", body, proof);
    try {
      return Objects.requireNonNull(codec.verifyCompensated(recovery, local, response));
    } catch (RuntimeException failure) {
      throw new KeycloakProtectedAccounts.RemoteFailure(0);
    }
  }

  public ProtectedAccountCodec.VerifiedCommitted consumedStatus(
      ProtectedAccountCodec.VerifiedCreated created, LocalOutcome local) {
    outsideSql();
    Objects.requireNonNull(created); Objects.requireNonNull(local);
    var actor = actors.require(TaskPurpose.INVITE_RESERVATIONS);
    if (actor == null || actor.actor() != TaskActor.SETUP || !"backend-setup".equals(actor.clientId())) throw invalid();
    byte[] body = codec.consumedStatusBody(created);
    String proof = codec.consumedStatusProof(created, actor);
    var token = broker.issue(TaskPurpose.INVITE_RESERVATIONS);
    if (token.actor() != TaskActor.SETUP || token.accessToken() == null || token.accessToken().isBlank()) throw invalid();
    URI route = UriComponentsBuilder.fromUri(authServer).pathSegment("realms", realmName, "oriso-protected-invitations", "v1", "approvals", created.approvalId().toString(), "status").build().encode().toUri();
    var headers = new HttpHeaders(); headers.setBearerAuth(token.accessToken());
    headers.setContentType(MediaType.APPLICATION_JSON); headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    headers.set("X-ORISO-Task-Context", proof);
    try {
      var response = http.exchange(route, HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class);
      if (response.getStatusCode().value() != 200 || response.getBody() == null) throw new KeycloakProtectedAccounts.RemoteFailure(response.getStatusCode().value());
      try { return Objects.requireNonNull(codec.verifyConsumedStatus(created, local, response.getBody())); }
      catch (RuntimeException invalid) { throw new KeycloakProtectedAccounts.RemoteFailure(0); }
    } catch (RestClientResponseException failure) { throw new KeycloakProtectedAccounts.RemoteFailure(failure.getStatusCode().value()); }
    catch (RestClientException failure) { throw new KeycloakProtectedAccounts.RemoteFailure(0); }
  }

  private TaskActorCredentials cleanupActor() {
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    if (actor == null
        || actor.actor() != TaskActor.ACCOUNT_ADMIN
        || !"backend-account-admin".equals(actor.clientId())
        || actor.serviceSubject() == null) throw invalid();
    try {
      if (!UUID.fromString(actor.serviceSubject()).toString().equals(actor.serviceSubject()))
        throw invalid();
    } catch (RuntimeException failure) {
      throw invalid();
    }
    return actor;
  }

  private byte[] call(UUID approval, UUID attempt, String suffix, byte[] body, String proof) {
    var token = broker.issue(TaskPurpose.ACCOUNT_MAINTENANCE);
    if (token.actor() != TaskActor.ACCOUNT_ADMIN
        || token.accessToken() == null
        || token.accessToken().isBlank()) throw invalid();
    URI route =
        UriComponentsBuilder.fromUri(authServer)
            .pathSegment(
                "realms",
                realmName,
                "oriso-protected-invitations",
                "v1",
                "approvals",
                approval.toString(),
                "attempts",
                attempt.toString(),
                suffix)
            .build()
            .encode()
            .toUri();
    var headers = new HttpHeaders();
    headers.setBearerAuth(token.accessToken());
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    headers.set("X-ORISO-Task-Context", proof);
    try {
      var response =
          http.exchange(route, HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class);
      int status = response.getStatusCode().value();
      if (status != 200 || response.getBody() == null)
        throw new KeycloakProtectedAccounts.RemoteFailure(status);
      return response.getBody();
    } catch (RestClientResponseException failure) {
      throw new KeycloakProtectedAccounts.RemoteFailure(failure.getStatusCode().value());
    } catch (RestClientException failure) {
      throw new KeycloakProtectedAccounts.RemoteFailure(0);
    }
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Protected account recovery requires completed local SQL");
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid protected account recovery transport binding");
  }
}
