package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationRestrictionCodec.FrozenCancellation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationRestrictionCodec.VerifiedRestriction;
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

/** Fixed reducing native routes, separate from human approval and account activation. */
public final class KeycloakProtectedRestrictions {
  private final RestTemplate http;
  private final TaskActorConfiguration actors;
  private final TaskActorTokenBroker broker;
  private final ProtectedInvitationCodec approvals;
  private final ProtectedInvitationRestrictionCodec restrictions;
  private final URI authServer;
  private final ProtectedCleanupStatusCodec cleanup;

  public KeycloakProtectedRestrictions(
      RestTemplate http,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      ProtectedInvitationCodec approvals,
      ProtectedInvitationRestrictionCodec restrictions,
      String authServer,
      String realmName) {
    this(http, actors, broker, approvals, restrictions, authServer, realmName, null);
  }

  public KeycloakProtectedRestrictions(
      RestTemplate http,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      ProtectedInvitationCodec approvals,
      ProtectedInvitationRestrictionCodec restrictions,
      String authServer,
      String realmName,
      ProtectedCleanupStatusCodec cleanup) {
    this.cleanup = cleanup;
    this.http = Objects.requireNonNull(http);
    this.actors = Objects.requireNonNull(actors);
    this.broker = Objects.requireNonNull(broker);
    this.approvals = Objects.requireNonNull(approvals);
    this.restrictions = Objects.requireNonNull(restrictions);
    try {
      URI server = URI.create(authServer);
      if (!approvals.realmName().equals(realmName)
          || !("http".equalsIgnoreCase(server.getScheme())
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

  public VerifiedRestriction cancel(FrozenCancellation frozen) {
    requireOutsideSql();
    String body = restrictions.cancellationBody(frozen);
    var actor = actors.require(TaskPurpose.INVITE_RESERVATIONS);
    String proof = restrictions.cancelProof(frozen, actor);
    return call(frozen, "cancel", body, proof, false);
  }

  public VerifiedRestriction reconcile(FrozenCancellation frozen) {
    requireOutsideSql();
    restrictions.cancellationBody(frozen);
    String body = approvals.statusBody(frozen.approval());
    var actor = actors.require(TaskPurpose.INVITE_RESERVATIONS);
    String proof = approvals.statusProof(frozen.approval(), actor);
    return call(frozen, "status", body, proof, true);
  }

  /** Independent native terminal read; never calls cancel, recover or compensate. */
  public ProtectedCleanupStatusCodec.VerifiedFinalStatus cleanupStatus(
      FrozenCancellation frozen,
      VerifiedRestriction original,
      ProtectedAccountCodec.VerifiedRecovery recovery,
      AccountLifecycleDtos.LocalOutcome local) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()
        || !TransactionSynchronizationManager.getResourceMap().isEmpty())
      throw new IllegalStateException("Cleanup status requires completed local SQL");
    if (cleanup == null || frozen == null || original == null || recovery == null || local == null)
      throw invalid();
    var actor = actors.require(TaskPurpose.INVITE_RESERVATIONS);
    String proof = restrictions.cleanupStatusProof(frozen, actor);
    var token = broker.issue(TaskPurpose.INVITE_RESERVATIONS);
    String bearer = token.accessToken();
    if (token.actor() != de.caritas.cob.userservice.api.config.auth.TaskActor.SETUP
        || bearer == null
        || bearer.isBlank()
        || bearer.length() > 65536
        || bearer
            .codePoints()
            .anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) throw invalid();
    var headers = new HttpHeaders();
    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    headers.setCacheControl("no-store");
    headers.setBearerAuth(bearer);
    headers.set("X-ORISO-Task-Context", proof);
    var uri =
        UriComponentsBuilder.fromUri(authServer)
            .pathSegment(
                "realms",
                approvals.realmName(),
                "oriso-protected-invitations",
                "v1",
                "approvals",
                frozen.approval().approvalId().toString(),
                "cleanup-status")
            .build()
            .encode()
            .toUri();
    try {
      var response =
          http.exchange(uri, HttpMethod.GET, new HttpEntity<Void>(headers), byte[].class);
      if (response.getStatusCode().value() != 200)
        throw new KeycloakProtectedInvitations.RemoteFailure(response.getStatusCode().value());
      try {
        return cleanup.verify(frozen, original, recovery, local, response.getBody());
      } catch (RuntimeException invalid) {
        throw new KeycloakProtectedInvitations.RemoteFailure(0);
      }
    } catch (RestClientResponseException failure) {
      throw new KeycloakProtectedInvitations.RemoteFailure(failure.getStatusCode().value());
    } catch (RestClientException failure) {
      throw new KeycloakProtectedInvitations.RemoteFailure(0);
    }
  }

  private VerifiedRestriction call(
      FrozenCancellation frozen, String route, String body, String proof, boolean statusRead) {
    var token = broker.issue(TaskPurpose.INVITE_RESERVATIONS);
    String bearer = token.accessToken();
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
    headers.set("X-ORISO-Task-Context", proof);
    var uri =
        UriComponentsBuilder.fromUri(authServer)
            .pathSegment(
                "realms",
                approvals.realmName(),
                "oriso-protected-invitations",
                "v1",
                "approvals",
                frozen.approval().approvalId().toString(),
                route)
            .build()
            .encode()
            .toUri();
    try {
      var response =
          http.exchange(
              uri,
              HttpMethod.POST,
              new HttpEntity<>(body.getBytes(StandardCharsets.UTF_8), headers),
              byte[].class);
      if (response.getStatusCode().value() != 200)
        throw new KeycloakProtectedInvitations.RemoteFailure(0);
      return restrictions.verify(frozen, response.getBody(), statusRead);
    } catch (RestClientResponseException failure) {
      throw new KeycloakProtectedInvitations.RemoteFailure(failure.getStatusCode().value());
    } catch (RestClientException | IllegalArgumentException failure) {
      throw new KeycloakProtectedInvitations.RemoteFailure(0);
    }
  }

  private static void requireOutsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Receiver commands require a completed local transaction");
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid protected restriction transport binding");
  }
}
