package de.caritas.cob.userservice.api.adapters.keycloak.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.port.out.AccountInactivityIdentityTransport;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService.ClaimedWork;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Fixed read/deactivate/restore wire from the actual persisted inactivity owner only. */
public final class KeycloakInactivityMaintenance implements AccountInactivityIdentityTransport {
  private final RestTemplate http;
  private final TaskActorConfiguration actors;
  private final TaskActorTokenBroker broker;
  private final AccountLifecycleCodec codec;
  private final URI server;
  private final String realmName, realmId, issuer;

  public KeycloakInactivityMaintenance(
      RestTemplate http,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      String server,
      String realmName,
      String realmId,
      String publicIssuer) {
    this.http = Objects.requireNonNull(http);
    this.actors = Objects.requireNonNull(actors);
    this.broker = Objects.requireNonNull(broker);
    this.codec = Objects.requireNonNull(codec);
    this.server = URI.create(server);
    this.realmName = Objects.requireNonNull(realmName);
    this.realmId = Objects.requireNonNull(realmId);
    this.issuer = Objects.requireNonNull(publicIssuer);
    if (!Set.of("http", "https").contains(this.server.getScheme())
        || this.server.getHost() == null
        || this.server.getRawUserInfo() != null
        || this.server.getRawQuery() != null
        || this.server.getRawFragment() != null
        || realmName.isBlank()
        || realmName.length() > 255
        || realmId.isBlank()
        || realmId.length() > 255
        || !Set.of("http", "https").contains(URI.create(issuer).getScheme())) throw unavailable();
  }

  @Override
  public Projection read(ClaimedWork work) {
    requireDispatch(work);
    JsonNode value =
        codec.read(
            call(
                work,
                "read",
                codec.canonical(Map.of("target", target(work), "workflow", workflow(work)))),
            JsonNode.class);
    fields(
        value,
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
            "state"));
    for (String field : List.of("id", "username", "state"))
      if (!value.path(field).isTextual()) throw unavailable();
    for (String field : List.of("email", "firstName", "lastName", "tenantId", "preferredLanguage"))
      if (!value.path(field).isNull() && !value.path(field).isTextual()) throw unavailable();
    for (String field : List.of("enabled", "emailVerified", "passwordChangeRequired"))
      if (!value.path(field).isBoolean()) throw unavailable();
    if (!work.identityId().equals(value.path("id").textValue())
        || !Objects.equals(
            work.tenantId() == null ? null : work.tenantId().toString(),
            value.path("tenantId").isNull() ? null : value.path("tenantId").textValue())
        || !value.path("state").textValue().matches("[0-9a-f]{64}")
        || value.path("username").textValue().isBlank()
        || value.path("username").textValue().length() > 255
        || !value.path("effectiveRoles").isArray()
        || value.path("effectiveRoles").isEmpty()
        || value.path("effectiveRoles").size() > 32) throw unavailable();
    var roles = new ArrayList<String>();
    for (var role : value.path("effectiveRoles")) {
      if (!role.isTextual()
          || role.textValue().isBlank()
          || role.textValue().length() > 255
          || roles.contains(role.textValue())) throw unavailable();
      roles.add(role.textValue());
    }
    JsonNode local = codec.read(work.originalSourceJson(), JsonNode.class);
    for (String table : List.of("user", "consultant", "admin"))
      for (var row : local.path(table)) {
        if (!row.path("username").isTextual()
            || !new de.caritas.cob.userservice.api.helper.UsernameTranscoder()
                .decodeUsername(row.path("username").textValue())
                .equals(value.path("username").textValue())) throw unavailable();
      }
    work.requireCurrent();
    return new Projection(
        work.identityId(),
        work.tenantId(),
        value.path("username").textValue(),
        value.path("enabled").booleanValue(),
        roles,
        value.path("state").textValue());
  }

  @Override
  public Frozen freeze(ClaimedWork work, Projection current, boolean restoreEnabled) {
    requireDispatch(work);
    if (current == null
        || !work.identityId().equals(current.id())
        || !Objects.equals(work.tenantId(), current.tenantId())
        || current.state() == null
        || !current.state().matches("[0-9a-f]{64}")) throw unavailable();
    return new Frozen(codec.canonical(body(work, current.state(), restoreEnabled)));
  }

  @Override
  public void verifyFrozen(ClaimedWork work, Frozen frozen, boolean restoreEnabled) {
    work.requireCurrent();
    if (work.terminalOnly()
        || frozen == null
        || frozen.body() == null
        || frozen.body().length() > 32768) throw unavailable();
    JsonNode value = codec.read(frozen.body(), JsonNode.class);
    String state = value.path("expectedState").textValue();
    if (state == null
        || !state.matches("[0-9a-f]{64}")
        || !frozen.body().equals(codec.canonical(value))
        || !codec.canonical(body(work, state, restoreEnabled)).equals(frozen.body()))
      throw unavailable();
  }

  @Override
  public Outcome apply(ClaimedWork work, Frozen frozen) {
    requireDispatch(work);
    JsonNode value = codec.read(frozen.body(), JsonNode.class);
    boolean enabled = value.path("change").path("restoreEnabled").booleanValue();
    verifyFrozen(work, frozen, enabled);
    try {
      return new Applied(verifyApplied(work, frozen, call(work, command(work), frozen.body())));
    } catch (NativeNoEffect noEffect) {
      return new NoEffect(noEffect.status, noEffect.reply);
    }
  }

  @Override
  public String verifyApplied(ClaimedWork work, Frozen frozen, String receipt) {
    work.requireCurrent();
    JsonNode value = codec.read(frozen.body(), JsonNode.class);
    verifyFrozen(work, frozen, value.path("change").path("restoreEnabled").booleanValue());
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    return switch (work.action()) {
      case "SUSPEND" ->
          codec.verifyMaintenanceInactivitySuspendReceipt(receipt, realmId, actor, value);
      case "RESTORE" ->
          codec.verifyMaintenanceInactivityRestoreReceipt(receipt, realmId, actor, value);
      default -> throw unavailable();
    };
  }

  @Override
  public NoEffect verifyNoEffect(ClaimedWork work, Frozen frozen, String receipt) {
    work.requireCurrent();
    JsonNode value = codec.read(frozen.body(), JsonNode.class);
    fields(value, Set.of("target", "workflow", "operationId", "expectedState", "change"));
    if (!work.workId().equals(value.path("operationId").textValue())
        || !codec.canonical(target(work)).equals(codec.canonical(value.path("target")))
        || !codec.canonical(workflow(work)).equals(codec.canonical(value.path("workflow")))
        || !frozen.body().equals(codec.canonical(value))) throw unavailable();
    var verified =
        codec.verifyMaintenanceNoEffectReceipt(
            receipt,
            realmId,
            actors.require(TaskPurpose.ACCOUNT_MAINTENANCE),
            value,
            command(work));
    return new NoEffect(verified.status(), codec.canonical(codec.read(receipt, JsonNode.class)));
  }

  @Override
  public void verifyPriorSuspension(ClaimedWork restore, String body, String receipt) {
    requireDispatch(restore);
    if (!"RESTORE".equals(restore.action())
        || restore.suspensionOperationId() == null
        || body == null
        || body.length() > 32768
        || receipt == null) throw unavailable();
    JsonNode frozen = codec.read(body, JsonNode.class);
    fields(frozen, Set.of("target", "workflow", "operationId", "expectedState", "change"));
    JsonNode workflow = frozen.path("workflow");
    fields(
        workflow,
        Set.of(
            "v",
            "kind",
            "workId",
            "action",
            "rowDigest",
            "revision",
            "assignedMonths",
            "lastActivity",
            "dueAt",
            "suspensionOperationId"));
    JsonNode target = frozen.path("target");
    fields(target, Set.of("accountId", "tenantId", "origin"));
    JsonNode origin = target.path("origin");
    fields(
        origin,
        Set.of(
            "kind", "workflowId", "humanSubject", "humanIssuer", "humanClient", "humanAuthority"));
    if (!body.equals(codec.canonical(frozen))
        || !restore.suspensionOperationId().equals(frozen.path("operationId").textValue())
        || !restore.suspensionOperationId().equals(workflow.path("workId").textValue())
        || !restore.suspensionOperationId().equals(origin.path("workflowId").textValue())
        || !"SUSPEND".equals(workflow.path("action").textValue())
        || !"INACTIVITY".equals(workflow.path("kind").textValue())
        || !workflow.path("suspensionOperationId").isNull()
        || !workflow.path("v").isIntegralNumber()
        || workflow.path("v").intValue() != 1
        || !restore.identityId().equals(target.path("accountId").textValue())
        || !Objects.equals(
            restore.tenantId() == null ? null : restore.tenantId().toString(),
            target.path("tenantId").isNull() ? null : target.path("tenantId").textValue())
        || !frozen.path("change").isObject()
        || !frozen.path("change").isEmpty()) throw unavailable();
    codec.verifyMaintenanceInactivitySuspendReceipt(
        receipt, realmId, actors.require(TaskPurpose.ACCOUNT_MAINTENANCE), frozen);
  }

  @Override
  public String bindSuspensionCustody(String exactPrefix) {
    if (exactPrefix == null || exactPrefix.getBytes(StandardCharsets.UTF_8).length > 8192)
      throw unavailable();
    var value = codec.read(exactPrefix, JsonNode.class);
    String digest = codec.claimedInactivitySuspensionCustodyDigest(value);
    if (!exactPrefix.equals(codec.canonical(value))) throw unavailable();
    verifyCustodyNative(value);
    var sealed = ((com.fasterxml.jackson.databind.node.ObjectNode) value).deepCopy();
    sealed.put("bindingDigest", digest);
    String result = codec.canonical(sealed);
    if (result.getBytes(StandardCharsets.UTF_8).length > 8192) throw unavailable();
    return result;
  }

  @Override
  public void verifySuspensionCustody(String exactSealedCustody) {
    if (exactSealedCustody == null
        || exactSealedCustody.getBytes(StandardCharsets.UTF_8).length > 8192) throw unavailable();
    var value = codec.read(exactSealedCustody, JsonNode.class);
    fields(
        value,
        Set.of(
            "representation",
            "originalRow",
            "source",
            "nativeBody",
            "nativeReceipt",
            "phase",
            "bindingDigest"));
    if (!exactSealedCustody.equals(codec.canonical(value))
        || !value.path("bindingDigest").isTextual()
        || !value.path("bindingDigest").textValue().matches("[0-9a-f]{64}")) throw unavailable();
    var prefix = ((com.fasterxml.jackson.databind.node.ObjectNode) value).deepCopy();
    prefix.remove("bindingDigest");
    String expected = codec.claimedInactivitySuspensionCustodyDigest(prefix);
    if (!java.security.MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII),
        value.path("bindingDigest").textValue().getBytes(StandardCharsets.US_ASCII)))
      throw unavailable();
    verifyCustodyNative(prefix);
  }

  private void verifyCustodyNative(JsonNode prefix) {
    String phase = prefix.path("phase").textValue();
    if ("ACKNOWLEDGED".equals(phase))
      codec.verifyMaintenanceInactivitySuspendReceipt(
          prefix.path("nativeReceipt").textValue(),
          realmId,
          actors.require(TaskPurpose.ACCOUNT_MAINTENANCE),
          prefix.path("nativeBody"));
    else if ("NO_EFFECT".equals(phase))
      codec.verifyMaintenanceNoEffectReceipt(
          prefix.path("nativeReceipt").textValue(),
          realmId,
          actors.require(TaskPurpose.ACCOUNT_MAINTENANCE),
          prefix.path("nativeBody"),
          "deactivate");
    else if (!Set.of("CAPTURED", "FROZEN").contains(phase)) throw unavailable();
  }

  private Map<String, Object> body(ClaimedWork work, String state, boolean restoreEnabled) {
    if (!Set.of("SUSPEND", "RESTORE").contains(work.action())) throw unavailable();
    return Map.of(
        "target",
        target(work),
        "workflow",
        workflow(work),
        "operationId",
        work.workId(),
        "expectedState",
        state,
        "change",
        work.action().equals("RESTORE") ? Map.of("restoreEnabled", restoreEnabled) : Map.of());
  }

  private Map<String, Object> workflow(ClaimedWork work) {
    JsonNode row = codec.read(work.originalRowJson(), JsonNode.class);
    String digest = codec.claimedInactivityRowDigest(row);
    Map<String, Object> result = new TreeMap<>();
    result.put("v", 1);
    result.put("kind", "INACTIVITY");
    result.put("workId", work.workId());
    result.put("action", work.action());
    result.put("rowDigest", digest);
    result.put("revision", row.path("revision").longValue());
    result.put("assignedMonths", row.path("assignedMonths").intValue());
    result.put("lastActivity", row.path("lastActivityMicros").longValue() / 1_000_000L);
    result.put("dueAt", row.path("dueAtMicros").longValue() / 1_000_000L);
    result.put("suspensionOperationId", work.suspensionOperationId());
    return result;
  }

  private Map<String, Object> target(ClaimedWork work) {
    JsonNode row = codec.read(work.originalRowJson(), JsonNode.class);
    JsonNode initiator = row.path("initiator");
    boolean human = "HUMAN_ADMIN".equals(initiator.path("kind").textValue());
    Map<String, Object> origin = new TreeMap<>();
    origin.put("kind", human ? "HUMAN_ADMIN" : "INACTIVITY");
    origin.put("workflowId", work.workId());
    origin.put("humanSubject", human ? initiator.path("subject").textValue() : null);
    origin.put("humanIssuer", human ? initiator.path("issuer").textValue() : null);
    origin.put("humanClient", human ? initiator.path("client").textValue() : null);
    origin.put("humanAuthority", human ? "PROTECTED_ACCOUNT_OPERATIONS_V1" : null);
    Map<String, Object> target = new TreeMap<>();
    target.put("accountId", work.identityId());
    target.put("tenantId", work.tenantId() == null ? null : work.tenantId().toString());
    target.put("origin", origin);
    return target;
  }

  private String command(ClaimedWork work) {
    return switch (work.action()) {
      case "SUSPEND" -> "deactivate";
      case "RESTORE" -> "reactivate";
      default -> throw unavailable();
    };
  }

  private String call(ClaimedWork work, String command, String body) {
    requireDispatch(work);
    if (!Set.of("read", "deactivate", "reactivate").contains(command)) throw unavailable();
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    var token = broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
    long now = Instant.now().getEpochSecond();
    if (token.actor() != TaskActor.ACCOUNT_ADMIN
        || token.purpose() != TaskPurpose.ACCOUNT_MAINTENANCE
        || actor.actor() != token.actor()
        || !actor.clientId().equals(token.clientId())
        || !actor.serviceSubject().equals(token.subject())
        || !issuer.equals(token.issuer())
        || token.accessToken() == null
        || token.accessToken().isBlank()
        || token.validatedJwtIssuedAt() <= 0
        || token.validatedJwtIssuedAt() > now + 5
        || token.validatedJwtExpiresAt() <= now
        || token.validatedJwtExpiresAt() <= token.validatedJwtIssuedAt()) throw unavailable();
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    headers.setCacheControl("no-store");
    headers.setBearerAuth(token.accessToken());
    if (work.humanInitiated()) headers.set("X-ORISO-Human-Authorization", work.rawHumanBearer());
    headers.set(
        "X-ORISO-Task-Context",
        codec.maintenanceProof(
            command,
            realmName,
            realmId,
            work.identityId(),
            actor,
            codec.read(body, JsonNode.class)));
    var uri =
        UriComponentsBuilder.fromUri(server)
            .pathSegment(
                "realms",
                realmName,
                "oriso-account-maintenance",
                "v1",
                "accounts",
                work.identityId(),
                command)
            .build()
            .encode()
            .toUri();
    try {
      work.requireCurrent();
      var response =
          http.exchange(
              uri,
              HttpMethod.POST,
              new HttpEntity<>(body.getBytes(StandardCharsets.UTF_8), headers),
              byte[].class);
      if (response.getStatusCode().value() != 200
          || !"no-store".equals(response.getHeaders().getCacheControl())
          || response.getBody() == null
          || response.getBody().length == 0
          || response.getBody().length > 32768) throw unavailable();
      return codec.utf8(response.getBody());
    } catch (RestClientResponseException failure) {
      if (!command.equals("read")
          && Set.of(403, 409).contains(failure.getStatusCode().value())
          && failure.getResponseHeaders() != null
          && "no-store".equals(failure.getResponseHeaders().getCacheControl())
          && failure.getResponseBodyAsByteArray().length > 0
          && failure.getResponseBodyAsByteArray().length <= 32768) {
        String reply = codec.utf8(failure.getResponseBodyAsByteArray());
        var verified =
            codec.verifyMaintenanceNoEffectReceipt(
                reply, realmId, actor, codec.read(body, JsonNode.class), command);
        if (verified.status() != failure.getStatusCode().value()) throw unavailable();
        throw new NativeNoEffect(
            verified.status(), codec.canonical(codec.read(reply, JsonNode.class)));
      }
      throw unavailable();
    } catch (NativeNoEffect terminal) {
      throw terminal;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private static void fields(JsonNode value, Set<String> expected) {
    if (value == null || !value.isObject()) throw unavailable();
    var actual = new HashSet<String>();
    value.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) throw unavailable();
  }

  private void requireDispatch(ClaimedWork work) {
    work.requireCurrent();
    if (work.terminalOnly() || TransactionSynchronizationManager.isActualTransactionActive())
      throw unavailable();
  }

  private static final class NativeNoEffect extends RuntimeException {
    final int status;
    final String reply;

    NativeNoEffect(int status, String reply) {
      super("Verified inactivity refusal");
      this.status = status;
      this.reply = reply;
    }
  }

  private static ServiceUnavailableException unavailable() {
    return new ServiceUnavailableException("Checked inactivity native maintenance is unavailable");
  }
}
