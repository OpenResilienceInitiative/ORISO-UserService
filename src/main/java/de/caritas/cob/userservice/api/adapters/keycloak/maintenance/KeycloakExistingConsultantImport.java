package de.caritas.cob.userservice.api.adapters.keycloak.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.port.out.ExistingConsultantImportTransport;
import de.caritas.cob.userservice.api.port.out.HumanAdminMaintenanceTransport.*;
import de.caritas.cob.userservice.api.service.identity.ExistingConsultantImportMaintenance.CheckedImport;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.client.*;
import org.springframework.web.util.UriComponentsBuilder;

/** IMPORT never carries a human bearer or obtains authority from a deserialized row alone. */
public final class KeycloakExistingConsultantImport implements ExistingConsultantImportTransport {
  private final RestTemplate http;
  private final TaskActorConfiguration actors;
  private final TaskActorTokenBroker broker;
  private final AccountLifecycleCodec codec;
  private final URI server;
  private final String realm, realmId, issuer;

  public KeycloakExistingConsultantImport(
      RestTemplate http,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      String server,
      String realm,
      String realmId,
      String issuer) {
    this.http = Objects.requireNonNull(http);
    this.actors = Objects.requireNonNull(actors);
    this.broker = Objects.requireNonNull(broker);
    this.codec = Objects.requireNonNull(codec);
    this.server = URI.create(server);
    this.realm = realm;
    this.realmId = realmId;
    this.issuer = issuer;
    if (!Set.of("http", "https").contains(this.server.getScheme())
        || this.server.getHost() == null
        || this.server.getRawUserInfo() != null
        || this.server.getRawQuery() != null
        || this.server.getRawFragment() != null
        || realm == null
        || realm.isBlank()
        || realmId == null
        || realmId.isBlank()
        || issuer == null
        || issuer.isBlank()) throw unavailable();
  }

  @Override
  public String sourceKey(
      de.caritas.cob.userservice.api.service.ConsultantImportService.ScopedExistingImport source) {
    source.requireLive();
    return codec.existingImportSourceKey(
        codec.read(
            codec.canonical(
                Map.of(
                    "sourceDigest",
                    source.sourceDigest(),
                    "recordNumber",
                    source.recordNumber(),
                    "accountId",
                    source.accountId(),
                    "tenantId",
                    source.tenantId().toString())),
            JsonNode.class));
  }

  @Override
  public OriginalBinding originalBinding(
      de.caritas.cob.userservice.api.service.ConsultantImportService.ScopedExistingImport source,
      String originalJson) {
    source.requireLive();
    var original = codec.read(originalJson, JsonNode.class);
    if (!source.sourceDigest().equals(original.path("sourceDigest").textValue())
        || source.recordNumber() != original.path("recordNumber").longValue()
        || !source.accountId().equals(original.path("accountId").textValue())
        || !source.tenantId().toString().equals(original.path("tenantId").textValue())
        || !codec
            .read(codec.canonical(source.record()), JsonNode.class)
            .equals(original.path("record"))) throw unavailable();
    String rowDigest = codec.existingImportRowDigest(original);
    return new OriginalBinding(
        rowDigest,
        codec.digest(
            "existing-consultant-import-intent/v1",
            Map.of(
                "rowDigest",
                rowDigest,
                "facts",
                Map.of("kind", "EXISTING_CONSULTANT_IMPORT", "agencies", source.agencies()))));
  }

  @Override
  public Projection read(CheckedImport source) {
    source.requireCurrent();
    var raw =
        codec.read(
            call(
                source,
                "read",
                codec.canonical(Map.of("target", target(source), "workflow", source.workflow()))),
            JsonNode.class);
    fields(
        raw,
        Set.of(
            "id",
            "username",
            "firstName",
            "lastName",
            "email",
            "enabled",
            "emailVerified",
            "tenantId",
            "preferredLanguage",
            "effectiveRoles",
            "passwordChangeRequired",
            "state"));
    for (String f : List.of("id", "username", "tenantId", "state"))
      if (!raw.path(f).isTextual()) throw unavailable();
    for (String f : List.of("firstName", "lastName", "email", "preferredLanguage"))
      if (!raw.path(f).isNull() && !raw.path(f).isTextual()) throw unavailable();
    for (String f : List.of("enabled", "emailVerified", "passwordChangeRequired"))
      if (!raw.path(f).isBoolean()) throw unavailable();
    if (!raw.path("effectiveRoles").isArray()
        || raw.path("effectiveRoles").isEmpty()
        || raw.path("effectiveRoles").size() > 100) throw unavailable();
    var roles = new ArrayList<String>();
    for (var role : raw.path("effectiveRoles")) {
      if (!role.isTextual()
          || role.textValue().isBlank()
          || role.textValue().length() > 255
          || roles.contains(role.textValue())) throw unavailable();
      roles.add(role.textValue());
    }
    if (!source.accountId().equals(raw.path("id").textValue())
        || !source.tenantId().toString().equals(raw.path("tenantId").textValue())
        || !raw.path("state").textValue().matches("[0-9a-f]{64}")) throw unavailable();
    return new Projection(
        source.accountId(),
        raw.path("username").textValue(),
        text(raw, "email"),
        text(raw, "firstName"),
        text(raw, "lastName"),
        raw.path("enabled").booleanValue(),
        raw.path("emailVerified").booleanValue(),
        source.tenantId(),
        text(raw, "preferredLanguage"),
        roles,
        raw.path("passwordChangeRequired").booleanValue(),
        raw.path("state").textValue());
  }

  private static String text(JsonNode node, String f) {
    return node.path(f).isNull() ? null : node.path(f).textValue();
  }

  private static void fields(JsonNode n, Set<String> wanted) {
    var found = new HashSet<String>();
    n.fieldNames().forEachRemaining(found::add);
    if (!n.isObject() || !wanted.equals(found)) throw unavailable();
  }

  private Map<String, Object> target(CheckedImport source) {
    var origin = new TreeMap<String, Object>();
    origin.put("kind", "IMPORT");
    origin.put("workflowId", source.workId().toString());
    for (String f : List.of("humanSubject", "humanIssuer", "humanClient", "humanAuthority"))
      origin.put(f, null);
    return Map.of(
        "accountId",
        source.accountId(),
        "tenantId",
        source.tenantId().toString(),
        "origin",
        origin);
  }

  private String binding(String digest, JsonNode body) {
    return codec.digest(
        "existing-consultant-import-command/v1", Map.of("requestDigest", digest, "body", body));
  }

  @Override
  public FrozenProfile freeze(CheckedImport source, String state, String group) {
    source.requireCurrent();
    if (state == null
        || !state.matches("[0-9a-f]{64}")
        || group == null
        || !group.matches("/human-roles/[a-z0-9-]{1,80}")) throw unavailable();
    var body =
        codec.read(
            codec.canonical(
                Map.of(
                    "target",
                    target(source),
                    "workflow",
                    source.workflow(),
                    "operationId",
                    source.workId().toString(),
                    "expectedState",
                    state,
                    "change",
                    Map.of("group", group))),
            JsonNode.class);
    return new FrozenProfile(codec.canonical(body), binding(source.requestDigest(), body));
  }

  private JsonNode checked(CheckedImport source, FrozenProfile frozen) {
    source.requireCurrent();
    if (frozen == null
        || frozen.body() == null
        || frozen.body().length() > 16384
        || frozen.bindingDigest() == null
        || !frozen.bindingDigest().matches("[0-9a-f]{64}")) throw unavailable();
    var body = codec.read(frozen.body(), JsonNode.class);
    fields(body, Set.of("target", "workflow", "operationId", "expectedState", "change"));
    fields(body.path("change"), Set.of("group"));
    String group = body.path("change").path("group").textValue(),
        state = body.path("expectedState").textValue();
    if (group == null
        || !group.matches("/human-roles/[a-z0-9-]{1,80}")
        || state == null
        || !state.matches("[0-9a-f]{64}")
        || !source.workId().toString().equals(body.path("operationId").textValue())
        || !codec.read(codec.canonical(target(source)), JsonNode.class).equals(body.path("target"))
        || !codec
            .read(codec.canonical(source.workflow()), JsonNode.class)
            .equals(body.path("workflow"))
        || !frozen.body().equals(codec.canonical(body))
        || !MessageDigest.isEqual(
            binding(source.requestDigest(), body).getBytes(StandardCharsets.US_ASCII),
            frozen.bindingDigest().getBytes(StandardCharsets.US_ASCII))) throw unavailable();
    return body;
  }

  @Override
  public ProfileOutcome execute(CheckedImport source, FrozenProfile frozen) {
    checked(source, frozen);
    try {
      return new ProfileApplied(
          verifyReceipt(source, frozen, call(source, "ordinary-profile", frozen.body())));
    } catch (Terminal terminal) {
      return verifyNoEffect(source, frozen, terminal.receipt);
    }
  }

  @Override
  public String verifyReceipt(CheckedImport source, FrozenProfile frozen, String receipt) {
    return codec.verifyMaintenanceOrdinaryProfileReceipt(
        receipt, realmId, actors.require(TaskPurpose.ACCOUNT_MAINTENANCE), checked(source, frozen));
  }

  @Override
  public ProfileNoEffect verifyNoEffect(
      CheckedImport source, FrozenProfile frozen, String receipt) {
    var result =
        codec.verifyMaintenanceNoEffectReceipt(
            receipt,
            realmId,
            actors.require(TaskPurpose.ACCOUNT_MAINTENANCE),
            checked(source, frozen),
            "ordinary-profile");
    return new ProfileNoEffect(
        result.status(), codec.canonical(codec.read(receipt, JsonNode.class)));
  }

  @Override
  public void verifyStoredNoEffect(
      CheckedImport source, String operation, String digest, FrozenProfile frozen, String receipt) {
    source.requireCurrent();
    StoredMaintenanceNoEffect.verify(
        codec,
        realmId,
        actors.require(TaskPurpose.ACCOUNT_MAINTENANCE),
        source.accountId(),
        source.tenantId(),
        "IMPORT",
        UUID.fromString(operation),
        digest,
        frozen,
        receipt);
  }

  private String call(CheckedImport source, String command, String body) {
    source.requireCurrent();
    if (!Set.of("read", "ordinary-profile").contains(command)) throw unavailable();
    var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
    var token = broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
    long now = Instant.now().getEpochSecond();
    if (actor.actor() != TaskActor.ACCOUNT_ADMIN
        || token.actor() != TaskActor.ACCOUNT_ADMIN
        || token.purpose() != TaskPurpose.ACCOUNT_MAINTENANCE
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
    headers.set(
        "X-ORISO-Task-Context",
        codec.maintenanceProof(
            command, realm, realmId, source.accountId(), actor, codec.read(body, JsonNode.class)));
    URI uri =
        UriComponentsBuilder.fromUri(server)
            .pathSegment(
                "realms",
                realm,
                "oriso-account-maintenance",
                "v1",
                "accounts",
                source.accountId(),
                command)
            .build()
            .encode()
            .toUri();
    try {
      source.requireCurrent();
      var result =
          http.exchange(
              uri,
              HttpMethod.POST,
              new HttpEntity<>(body.getBytes(StandardCharsets.UTF_8), headers),
              byte[].class);
      var raw = result.getBody();
      if (result.getStatusCode().value() != 200
          || raw == null
          || raw.length == 0
          || raw.length > 16384
          || !"no-store".equals(result.getHeaders().getCacheControl())) throw unavailable();
      return codec.utf8(raw);
    } catch (RestClientResponseException refusal) {
      if (command.equals("ordinary-profile")
          && Set.of(403, 409).contains(refusal.getStatusCode().value())
          && refusal.getResponseHeaders() != null
          && "no-store".equals(refusal.getResponseHeaders().getCacheControl())
          && refusal.getResponseBodyAsByteArray().length > 0
          && refusal.getResponseBodyAsByteArray().length <= 16384) {
        try {
          String receipt = codec.utf8(refusal.getResponseBodyAsByteArray());
          var verified =
              codec.verifyMaintenanceNoEffectReceipt(
                  receipt, realmId, actor, codec.read(body, JsonNode.class), command);
          if (verified.status() != refusal.getStatusCode().value()) throw unavailable();
          throw new Terminal(receipt);
        } catch (Terminal terminal) {
          throw terminal;
        } catch (RuntimeException unknown) {
          /* Unsigned statuses keep the original pending action. */
        }
      }
      throw unavailable();
    } catch (RuntimeException unknown) {
      throw unavailable();
    }
  }

  private static final class Terminal extends RuntimeException {
    private final String receipt;

    private Terminal(String receipt) {
      super("Verified configured import refusal");
      this.receipt = receipt;
    }
  }

  private static ServiceUnavailableException unavailable() {
    return new ServiceUnavailableException("Configured import native outcome remains unknown");
  }
}
