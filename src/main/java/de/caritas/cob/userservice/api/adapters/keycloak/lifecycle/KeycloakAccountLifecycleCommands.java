package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;

import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Default-off seam: the existing account broker issuance fence deliberately remains in force. */
public final class KeycloakAccountLifecycleCommands implements IdentityProvisioningCommands {
  private final RestTemplate http;
  private final TaskActorConfiguration actors;
  private final TaskActorTokenBroker broker;
  private final AccountLifecycleCodec codec;
  private final String authServer;
  private final String realmName;
  private final String realmId;

  public KeycloakAccountLifecycleCommands(
      RestTemplate http,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      String authServer,
      String realmName,
      String realmId) {
    this.http = http;
    this.actors = actors;
    this.broker = broker;
    this.codec = codec;
    this.authServer = authServer;
    this.realmName = realmName;
    this.realmId = realmId;
    if (realmName == null || realmName.isBlank() || realmId == null || realmId.isBlank())
      throw new IllegalArgumentException("Actual lifecycle realm binding is required");
  }

  @Override
  public AttemptResult create(UUID id, CreateRequest request) {
    if (!codec.identity(request.identity().scope(), request.account()).equals(request.identity()))
      throw new IllegalArgumentException("Creation payload does not match durable fingerprint");
    return call(
        id,
        "",
        "account.create",
        HttpMethod.PUT,
        TaskPurpose.ACCOUNT_PROVISIONING,
        request,
        AttemptResult.class);
  }

  @Override
  public AccountProjection ownedRead(UUID id, OwnedReadRequest request) {
    return call(
        id,
        "owned-read",
        "account.owned-read",
        HttpMethod.POST,
        TaskPurpose.ACCOUNT_PROVISIONING,
        request,
        AccountProjection.class);
  }

  @Override
  public AttemptResult recover(UUID id, RecoverRequest request) {
    return call(
        id,
        "recover",
        "account.creation-recover",
        HttpMethod.POST,
        TaskPurpose.ACCOUNT_MAINTENANCE,
        request,
        AttemptResult.class);
  }

  @Override
  public AttemptResult commit(UUID id, CommitRequest request) {
    return call(
        id,
        "commit",
        "account.commit",
        HttpMethod.POST,
        TaskPurpose.ACCOUNT_PROVISIONING,
        request,
        AttemptResult.class);
  }

  @Override
  public AttemptResult compensate(UUID id, CompensateRequest request) {
    return call(
        id,
        "compensate",
        "account.compensate",
        HttpMethod.POST,
        TaskPurpose.ACCOUNT_MAINTENANCE,
        request,
        AttemptResult.class);
  }

  private <T> T call(
      UUID id,
      String suffix,
      String operation,
      HttpMethod method,
      TaskPurpose purpose,
      Object request,
      Class<T> responseType) {
    var actor = actors.require(purpose);
    String bearer = switch (purpose) {
      case ACCOUNT_PROVISIONING -> broker.issueCheckedProvisioning(purpose).accessToken();
      case ACCOUNT_MAINTENANCE -> broker.issueCheckedMaintenance(purpose).accessToken();
      default -> throw new IllegalArgumentException("Unsupported account lifecycle purpose");
    };
    String body = codec.canonical(request);
    if (body.getBytes(StandardCharsets.UTF_8).length > 65536)
      throw new IllegalArgumentException("Account lifecycle request exceeds limit");
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
    headers.setBearerAuth(bearer);
    headers.setCacheControl("no-store");
    headers.set(
        "X-ORISO-Task-Context",
        codec.proof(operation, method.name(), realmName, realmId, id, purpose, actor, request));
    var builder =
        UriComponentsBuilder.fromUriString(authServer)
            .pathSegment(
                "realms", realmName, "oriso-account-lifecycle", "v1", "attempts", id.toString());
    if (!suffix.isEmpty()) builder.pathSegment(suffix);
    URI uri = builder.build().encode().toUri();
    try {
      var response =
          http.exchange(
              uri,
              method,
              new HttpEntity<>(body.getBytes(StandardCharsets.UTF_8), headers),
              byte[].class);
      if (!(response.getStatusCode().value() == 200
          || (method == HttpMethod.PUT && response.getStatusCode().value() == 201)))
        throw new RemoteFailure(0);
      T parsed = codec.read(codec.utf8(response.getBody()), responseType);
      if (parsed instanceof AttemptResult receipt) {
        CreationIdentity identity =
            switch (request) {
              case CreateRequest create -> create.identity();
              case RecoverRequest recover -> recover.identity();
              case CommitRequest commit -> commit.identity();
              case CompensateRequest compensate -> compensate.identity();
              default -> throw new IllegalArgumentException("Unsupported lifecycle result");
            };
        if (!id.equals(receipt.attemptId()))
          throw new IllegalArgumentException("Foreign lifecycle attempt");
        if (!"ABANDONED".equals(receipt.state())) {
          var originalOwner = actors.require(TaskPurpose.ACCOUNT_PROVISIONING);
          codec.verifyCreationProof(
              receipt.creationProof(),
              realmName,
              realmId,
              originalOwner.clientId(),
              originalOwner.serviceSubject(),
              id,
              receipt.accountId(),
              identity);
        }
      }
      return parsed;
    } catch (RestClientResponseException failure) {
      // Provider bodies and HTTP diagnostics can contain profile data or proof material.
      String confirmedCode = null;
      if ("account.create".equals(operation) && failure.getStatusCode().value() == 409) {
        try {
          byte[] raw = failure.getResponseBodyAsByteArray();
          if (raw.length <= 128) {
            var conflict = codec.read(codec.utf8(raw), CreationConflict.class);
            if (conflict.code() != null
                && java.util.Set.of("USERNAME_NOT_AVAILABLE", "EMAIL_NOT_AVAILABLE")
                    .contains(conflict.code())) confirmedCode = conflict.code();
          }
        } catch (IllegalArgumentException unknown) {
          /* Other conflict bodies disclose no availability. */
        }
      }
      throw new RemoteFailure(failure.getStatusCode().value(), confirmedCode);
    } catch (RestClientException failure) {
      throw new RemoteFailure(0);
    }
  }

  private record CreationConflict(String code) {}

  public static final class RemoteFailure extends RuntimeException {
    private final int status;
    private final String code;

    public RemoteFailure(int status) {
      this(status, null);
    }

    public RemoteFailure(int status, String code) {
      super("Account lifecycle command unavailable (status=" + status + ")");
      this.status = status;
      this.code = code;
    }

    public String code() {
      return code;
    }

    public int status() {
      return status;
    }
  }
}
