package de.caritas.cob.userservice.api.adapters.keycloak.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.port.out.HumanAdminMaintenanceTransport;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdate;
import de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance.CheckedAccount;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Exact native read/profile transport. Only the actual private application issuer supplies scope.
 */
public final class KeycloakHumanAdminMaintenance implements HumanAdminMaintenanceTransport {
  private final RestTemplate http;
  private final TaskActorConfiguration actors;
  private final TaskActorTokenBroker broker;
  private final AccountLifecycleCodec codec;
  private final URI authServer;
  private final String realmName, realmId;

  public KeycloakHumanAdminMaintenance(
      RestTemplate http,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      String authServer,
      String realmName,
      String realmId) {
    this.http = Objects.requireNonNull(http);
    this.actors = Objects.requireNonNull(actors);
    this.broker = Objects.requireNonNull(broker);
    this.codec = Objects.requireNonNull(codec);
    try {
      URI server = URI.create(authServer);
      if (!Set.of("http", "https").contains(server.getScheme())
          || server.getHost() == null
          || server.getRawUserInfo() != null
          || server.getRawQuery() != null
          || server.getRawFragment() != null
          || realmName == null
          || realmName.isBlank()
          || realmName.length() > 255
          || realmId == null
          || realmId.isBlank()
          || realmId.length() > 255) throw invalid();
      this.authServer = server;
      this.realmName = realmName;
      this.realmId = realmId;
    } catch (RuntimeException invalid) {
      throw invalid();
    }
  }

  private record Read(
      String id,
      String username,
      String email,
      String firstName,
      String lastName,
      Boolean enabled,
      Boolean emailVerified,
      String tenantId,
      String preferredLanguage,
      List<String> effectiveRoles,
      Boolean passwordChangeRequired,
      String state) {}

  @Override
  public Projection read(CheckedAccount account) {
    account.requireCurrent();
    try {
      String raw = call(account, "read", codec.canonical(Map.of("target", target(account))));
      JsonNode shape = codec.read(raw, JsonNode.class);
      var fields = new HashSet<String>();
      shape.fieldNames().forEachRemaining(fields::add);
      if (!shape.isObject()
          || !fields.equals(
              Set.of(
                  "id",
                  "username",
                  "email",
                  "firstName",
                  "lastName",
                  "enabled",
                  "emailVerified",
                  "tenantId",
                  "preferredLanguage",
                  "effectiveRoles",
                  "passwordChangeRequired",
                  "state"))) throw invalid();
      for (String field : List.of("id", "username", "state"))
        if (!shape.path(field).isTextual()) throw invalid();
      for (String field :
          List.of("email", "firstName", "lastName", "tenantId", "preferredLanguage"))
        if (!shape.path(field).isNull() && !shape.path(field).isTextual()) throw invalid();
      for (String field : List.of("enabled", "emailVerified", "passwordChangeRequired"))
        if (!shape.path(field).isBoolean()) throw invalid();
      if (!shape.path("effectiveRoles").isArray()) throw invalid();
      for (JsonNode role : shape.path("effectiveRoles")) if (!role.isTextual()) throw invalid();
      Read read = codec.read(raw, Read.class);
      if (!account.accountId().equals(read.id)
          || read.username == null
          || read.username.isBlank()
          || read.username.length() > 255
          || read.enabled == null
          || read.emailVerified == null
          || read.passwordChangeRequired == null
          || read.effectiveRoles == null
          || read.effectiveRoles.isEmpty()
          || read.effectiveRoles.size() > 32
          || new HashSet<>(read.effectiveRoles).size() != read.effectiveRoles.size()
          || read.effectiveRoles.stream()
              .anyMatch(r -> r == null || r.isBlank() || r.length() > 255)
          || read.state == null
          || !read.state.matches("[0-9a-f]{64}")) throw invalid();
      Long tenant = null;
      if (read.tenantId != null) {
        if (!read.tenantId.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        tenant = Long.valueOf(read.tenantId);
      }
      if (!Objects.equals(tenant, account.tenantId())) throw invalid();
      for (String value : Arrays.asList(read.email, read.firstName, read.lastName))
        if (value != null
            && (value.length() > 255 || value.chars().anyMatch(Character::isISOControl)))
          throw invalid();
      if (read.preferredLanguage != null
          && (read.preferredLanguage.isBlank() || read.preferredLanguage.length() > 20))
        throw invalid();
      return new Projection(
          read.id,
          read.username,
          read.email,
          read.firstName,
          read.lastName,
          read.enabled,
          read.emailVerified,
          tenant,
          read.preferredLanguage,
          read.effectiveRoles,
          read.passwordChangeRequired,
          read.state);
    } catch (ForbiddenException
        | ConflictException
        | NotFoundException
        | BadRequestException known) {
      throw known;
    } catch (RuntimeException invalid) {
      throw invalid();
    }
  }

  @Override
  public String profileRequestDigest(
      CheckedAccount account, IdentityProfileUpdate profile, UUID operation) {
    account.requireCurrent();
    if (!operation.equals(account.workflowId())
        || !Objects.equals(profile, account.desiredProfile())) throw invalid();
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    var binding = new TreeMap<String, Object>();
    binding.put("target", target(account));
    binding.put("operationId", operation.toString());
    binding.put("method", account.requestMethod());
    binding.put("route", account.requestPath());
    binding.put("originalSql", codec.read(account.originalSqlState(), JsonNode.class));
    binding.put("profile", profile);
    binding.put("ownerClient", actor.clientId());
    binding.put("ownerSubject", actor.serviceSubject());
    return codec.digest("human-admin-profile-request/v1", binding);
  }

  @Override
  public FrozenProfile freezeProfile(
      CheckedAccount account, IdentityProfileUpdate profile, UUID operation, String expectedState) {
    account.requireCurrent();
    if (expectedState == null || !expectedState.matches("[0-9a-f]{64}")) throw invalid();
    String request = profileRequestDigest(account, profile, operation);
    JsonNode body =
        codec.read(codec.canonical(body(account, profile, expectedState)), JsonNode.class);
    return new FrozenProfile(codec.canonical(body), bindingDigest(request, body));
  }

  @Override
  public String updateProfile(CheckedAccount account, FrozenProfile frozen) {
    try {
      checkedBody(account, frozen);
      String reply = call(account, "profile", frozen.body());
      return verifyProfileReceipt(account, frozen, reply);
    } catch (ForbiddenException
        | ConflictException
        | NotFoundException
        | BadRequestException known) {
      throw known;
    } catch (RuntimeException unavailable) {
      throw invalid();
    }
  }

  @Override
  public String verifyProfileReceipt(CheckedAccount account, FrozenProfile frozen, String receipt) {
    JsonNode body = checkedBody(account, frozen);
    try {
      return codec.verifyMaintenanceProfileReceipt(
          receipt, realmId, actors.require(TaskPurpose.ACCOUNT_MAINTENANCE), body);
    } catch (RuntimeException invalid) {
      throw invalid();
    }
  }

  private JsonNode checkedBody(CheckedAccount account, FrozenProfile frozen) {
    account.requireCurrent();
    try {
      if (frozen == null
          || frozen.body() == null
          || frozen.body().length() > 16384
          || frozen.bindingDigest() == null
          || !frozen.bindingDigest().matches("[0-9a-f]{64}")) throw invalid();
      JsonNode body = codec.read(frozen.body(), JsonNode.class);
      String state = body.path("expectedState").textValue();
      if (state == null
          || !state.matches("[0-9a-f]{64}")
          || !frozen.body().equals(codec.canonical(body))
          || !body.equals(
              codec.read(
                  codec.canonical(body(account, account.desiredProfile(), state)), JsonNode.class)))
        throw invalid();
      String expected =
          bindingDigest(
              profileRequestDigest(account, account.desiredProfile(), account.workflowId()), body);
      if (!MessageDigest.isEqual(
          expected.getBytes(StandardCharsets.US_ASCII),
          frozen.bindingDigest().getBytes(StandardCharsets.US_ASCII))) throw invalid();
      return body;
    } catch (RuntimeException invalid) {
      throw invalid();
    }
  }

  private String bindingDigest(String request, JsonNode body) {
    return codec.digest(
        "human-admin-profile-command/v1", Map.of("requestDigest", request, "body", body));
  }

  private Map<String, Object> body(
      CheckedAccount account, IdentityProfileUpdate profile, String state) {
    if (profile == null) throw invalid();
    var change = new TreeMap<String, Object>();
    change.put("firstName", profile.firstName());
    change.put("lastName", profile.lastName());
    change.put("email", profile.email());
    return Map.of(
        "target",
        target(account),
        "operationId",
        account.workflowId().toString(),
        "expectedState",
        state,
        "change",
        change);
  }

  private Map<String, Object> target(CheckedAccount account) {
    var origin = new TreeMap<String, Object>();
    origin.put("kind", "HUMAN_ADMIN");
    origin.put("workflowId", account.workflowId().toString());
    origin.put("humanSubject", account.humanSubject());
    origin.put("humanIssuer", account.humanIssuer());
    origin.put("humanClient", account.humanClient());
    origin.put("humanAuthority", "PROTECTED_ACCOUNT_OPERATIONS_V1");
    var target = new TreeMap<String, Object>();
    target.put("accountId", account.accountId());
    target.put("tenantId", account.tenantId() == null ? null : account.tenantId().toString());
    target.put("origin", origin);
    return target;
  }

  private String call(CheckedAccount account, String command, String frozen) {
    account.requireCurrent();
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || !Set.of("read", "profile").contains(command)) throw invalid();
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    var token = broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
    long now = Instant.now().getEpochSecond();
    if (token.actor() != TaskActor.ACCOUNT_ADMIN
        || token.purpose() != TaskPurpose.ACCOUNT_MAINTENANCE
        || actor.actor() != token.actor()
        || !actor.clientId().equals(token.clientId())
        || !actor.serviceSubject().equals(token.subject())
        || !account.humanIssuer().equals(token.issuer())
        || token.accessToken() == null
        || token.accessToken().isBlank()
        || token.validatedJwtIssuedAt() <= 0
        || token.validatedJwtIssuedAt() > now + 5
        || token.validatedJwtExpiresAt() <= now
        || token.validatedJwtExpiresAt() <= token.validatedJwtIssuedAt()) throw invalid();
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    headers.setCacheControl("no-store");
    headers.setBearerAuth(token.accessToken());
    headers.set("X-ORISO-Human-Authorization", account.rawHumanBearer());
    headers.set(
        "X-ORISO-Task-Context",
        codec.maintenanceProof(
            command,
            realmName,
            realmId,
            account.accountId(),
            actor,
            codec.read(frozen, JsonNode.class)));
    var uri =
        UriComponentsBuilder.fromUri(authServer)
            .pathSegment(
                "realms",
                realmName,
                "oriso-account-maintenance",
                "v1",
                "accounts",
                account.accountId(),
                command)
            .build()
            .encode()
            .toUri();
    try {
      account.requireCurrent();
      var response =
          http.exchange(
              uri,
              HttpMethod.POST,
              new HttpEntity<>(frozen.getBytes(StandardCharsets.UTF_8), headers),
              byte[].class);
      byte[] raw = response.getBody();
      if (response.getStatusCode().value() != 200
          || raw == null
          || raw.length == 0
          || raw.length > 16384
          || !"no-store".equals(response.getHeaders().getCacheControl())) throw invalid();
      return codec.utf8(raw);
    } catch (RestClientResponseException refusal) {
      switch (refusal.getStatusCode().value()) {
        case 400 -> throw new BadRequestException("Managed account request was rejected");
        case 403 -> throw new ForbiddenException("Managed account operation is not authorized");
        case 404 -> throw new NotFoundException("Managed account was not found");
        case 409 ->
            throw new ConflictException("The account operation conflicts with current state");
        default -> throw invalid();
      }
    } catch (RuntimeException unavailable) {
      throw invalid();
    }
  }

  private static ServiceUnavailableException invalid() {
    return new ServiceUnavailableException("Checked native admin maintenance is unavailable");
  }
}
