package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Separate protected wire; the ordinary lifecycle proof domain is unchanged. */
public final class ProtectedInvitationCodec {
  private final byte[] key;
  private final Clock clock;
  private final String realmName;
  private final String realmId;
  private final AccountLifecycleCodec json;
  private final ObjectMapper nodes = new ObjectMapper();

  public ProtectedInvitationCodec(byte[] key, Clock clock, String realmName, String realmId) {
    json = new AccountLifecycleCodec(key);
    if (clock == null
        || realmName == null
        || realmName.isBlank()
        || realmId == null
        || realmId.isBlank()) throw invalid();
    this.key = key.clone();
    this.clock = clock;
    this.realmName = realmName;
    this.realmId = realmId;
  }

  public FrozenApproval freeze(
      UUID approvalId, String requestJson, String issuerRealm, String issuerSubject) {
    try {
      if (approvalId == null || !realmId.equals(issuerRealm)) throw invalid();
      uuid(issuerSubject);
      JsonNode request = json.read(requestJson, JsonNode.class);
      fields(request, "scope");
      validateScope(request.path("scope"));
      String digest =
          json.digest(
              "protected-approval-request/v1",
              Map.of(
                  "request", request, "issuerRealm", issuerRealm, "issuerSubject", issuerSubject));
      return new FrozenApproval(
          approvalId, json.canonical(request), issuerRealm, issuerSubject, digest);
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  public VerifiedApproval verifyAuthorized(FrozenApproval frozen, String responseJson) {
    try {
      JsonNode scope = checkedScope(frozen);
      JsonNode response = json.read(responseJson, JsonNode.class);
      fields(
          response,
          "approvalId",
          "scopeDigest",
          "state",
          "version",
          "boundAttemptId",
          "fencedExecution",
          "approvalReceipt",
          "transitionReceipt",
          "approvalRequestDigest");
      String approvalId = frozen.approvalId().toString();
      String scopeDigest = hex(text(response, "scopeDigest", 64));
      if (!approvalId.equals(text(response, "approvalId", 36))
          || !"AUTHORIZED".equals(text(response, "state", 20))
          || number(response, "version") < 0
          || !response.path("boundAttemptId").isNull()
          || !response.path("fencedExecution").isNull()
          || !response.path("transitionReceipt").isNull()
          || !equal(frozen.requestDigest(), text(response, "approvalRequestDigest", 64)))
        throw invalid();
      JsonNode receipt = response.path("approvalReceipt");
      fields(
          receipt,
          "approvalId",
          "scopeDigest",
          "issuerSubject",
          "bindingVersion",
          "profileVersion",
          "validUntil",
          "approvalRequestDigest",
          "receipt");
      if (!approvalId.equals(text(receipt, "approvalId", 36))
          || !equal(scopeDigest, text(receipt, "scopeDigest", 64))
          || !frozen.issuerSubject().equals(text(receipt, "issuerSubject", 36))
          || number(receipt, "bindingVersion") <= 0
          || number(receipt, "profileVersion") <= 0
          || !equal(frozen.requestDigest(), text(receipt, "approvalRequestDigest", 64))
          || !scope.path("validUntil").asText().equals(text(receipt, "validUntil", 30))
          || !instant(text(receipt, "validUntil", 30)).isAfter(clock.instant())) throw invalid();
      ObjectNode authenticated = receipt.deepCopy();
      authenticated.remove("receipt");
      String encoded = encode(json.canonical(authenticated).getBytes(StandardCharsets.UTF_8));
      String expected =
          encoded
              + "."
              + HexFormat.of().formatHex(mac("protected-approval-receipt/v1\n" + encoded));
      if (!equal(expected, text(receipt, "receipt", 16384))) throw invalid();
      return new VerifiedApproval(
          frozen.approvalId(),
          scopeDigest,
          frozen.requestDigest(),
          json.canonical(receipt),
          json.canonical(response));
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  public String statusBody(FrozenApproval frozen) {
    JsonNode scope = checkedScope(frozen);
    ObjectNode body = nodes.createObjectNode();
    body.putNull("scopeDigest");
    body.set("workflowId", scope.path("workflowId"));
    body.set("inviteId", scope.path("inviteId"));
    body.set("authorizationRevision", scope.path("authorizationRevision"));
    body.putNull("attemptId");
    body.put("approvalRequestDigest", frozen.requestDigest());
    return json.canonical(body);
  }

  public String approvalBody(FrozenApproval frozen) {
    checkedScope(frozen);
    return frozen.requestJson();
  }

  public VerifiedApproval verifyAuthorized(FrozenApproval frozen, byte[] responseBytes) {
    return verifyAuthorized(frozen, json.utf8(responseBytes));
  }

  String realmName() {
    return realmName;
  }

  public String statusProof(FrozenApproval frozen, TaskActorCredentials actor) {
    JsonNode scope = checkedScope(frozen);
    if (actor == null
        || actor.actor() != TaskActor.SETUP
        || !scope.path("setupClient").asText().equals(actor.clientId())
        || !scope.path("setupSubject").asText().equals(actor.serviceSubject())) throw invalid();
    long now = clock.instant().getEpochSecond();
    Map<String, Object> claims = new TreeMap<>();
    claims.put("iss", "userservice");
    claims.put("aud", "oriso-protected-invitations");
    claims.put("purpose", "INVITE_RESERVATIONS");
    claims.put("operation", "protected-invitation.status");
    claims.put("method", "POST");
    claims.put("actor", "SETUP");
    claims.put("realm", realmName);
    claims.put("target", "realm/" + realmId + "/protected-invitation/" + frozen.approvalId());
    claims.put("taskClient", actor.clientId());
    claims.put("taskSubject", actor.serviceSubject());
    claims.put(
        "payloadDigest",
        json.digest(
            "protected-invitation-payload/v1", json.read(statusBody(frozen), JsonNode.class)));
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("nonce", UUID.randomUUID().toString().replace("-", ""));
    claims.put("iat", now);
    claims.put("exp", now + 60);
    String encoded = encode(json.canonical(claims).getBytes(StandardCharsets.UTF_8));
    return encoded + "." + encode(mac("protected-invitation-proof/v1\n" + encoded));
  }

  private JsonNode checkedScope(FrozenApproval frozen) {
    try {
      if (frozen == null) throw invalid();
      var checked =
          freeze(
              frozen.approvalId(),
              frozen.requestJson(),
              frozen.issuerRealm(),
              frozen.issuerSubject());
      if (!checked.requestJson().equals(frozen.requestJson())
          || !equal(checked.requestDigest(), frozen.requestDigest())) throw invalid();
      return json.read(checked.requestJson(), JsonNode.class).path("scope");
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  private void validateScope(JsonNode scope) {
    fields(
        scope,
        "workflowId",
        "inviteId",
        "authorizationRevision",
        "purpose",
        "recipient",
        "protectedKind",
        "targetTenantIds",
        "tenantMode",
        "reservationBinding",
        "agencyBinding",
        "protectedProfileId",
        "validUntil",
        "creatorClient",
        "creatorSubject",
        "setupClient",
        "setupSubject");
    String workflow = uuid(text(scope, "workflowId", 36));
    text(scope, "inviteId", 255);
    if (number(scope, "authorizationRevision") <= 0 || !"INVITE".equals(text(scope, "purpose", 20)))
      throw invalid();
    String recipient = text(scope, "recipient", 320);
    if (!recipient.equals(recipient.trim().toLowerCase(Locale.ROOT))
        || !recipient.matches("[^\\s@]+@[^\\s@]+")) throw invalid();
    JsonNode tenants = scope.path("targetTenantIds");
    if (!tenants.isArray()
        || tenants.size() != 1
        || !tenants.get(0).isIntegralNumber()
        || !tenants.get(0).canConvertToLong()) throw invalid();
    long tenant = tenants.get(0).longValue();
    String kind = text(scope, "protectedKind", 30), mode = text(scope, "tenantMode", 20);
    if (!("PLATFORM_ADMIN".equals(kind) && tenant == 0 && "EXISTING".equals(mode))
        && !("TENANT_ADMIN".equals(kind)
            && tenant > 0
            && Set.of("EXISTING", "RESERVED_NEW").contains(mode))) throw invalid();
    if (!"backend-account-create".equals(text(scope, "creatorClient", 255))
        || !"backend-setup".equals(text(scope, "setupClient", 255))) throw invalid();
    uuid(text(scope, "creatorSubject", 36));
    uuid(text(scope, "setupSubject", 36));
    JsonNode reservation = scope.path("reservationBinding");
    if ("RESERVED_NEW".equals(mode)) {
      fields(reservation, "tenantId", "invitationWorkflowId", "capabilityDigest");
      if (number(reservation, "tenantId") != tenant
          || !workflow.equals(uuid(text(reservation, "invitationWorkflowId", 36)))) throw invalid();
      hex(text(reservation, "capabilityDigest", 64));
    } else if (!reservation.isNull()) throw invalid();
    JsonNode agency = scope.path("agencyBinding");
    if (!agency.isNull()) {
      fields(agency, "agencyId", "tenantId");
      if (number(agency, "agencyId") <= 0
          || number(agency, "tenantId") != tenant
          || "PLATFORM_ADMIN".equals(kind)) throw invalid();
    }
    text(scope, "protectedProfileId", 255);
    instant(text(scope, "validUntil", 30));
  }

  private static void fields(JsonNode value, String... names) {
    if (value == null || !value.isObject()) throw invalid();
    Set<String> actual = new HashSet<>();
    value.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(Set.of(names))) throw invalid();
  }

  private static String text(JsonNode value, String name, int limit) {
    JsonNode field = value.path(name);
    if (!field.isTextual() || field.textValue().isBlank() || field.textValue().length() > limit)
      throw invalid();
    return field.textValue();
  }

  private static long number(JsonNode value, String name) {
    JsonNode field = value.path(name);
    if (!field.isIntegralNumber() || !field.canConvertToLong()) throw invalid();
    return field.longValue();
  }

  private static String uuid(String value) {
    if (value == null || !UUID.fromString(value).toString().equals(value)) throw invalid();
    return value;
  }

  private static String hex(String value) {
    if (!value.matches("[0-9a-f]{64}")) throw invalid();
    return value;
  }

  private static Instant instant(String value) {
    Instant parsed = Instant.parse(value);
    if (!parsed.toString().equals(value)) throw invalid();
    return parsed;
  }

  private static boolean equal(String left, String right) {
    return left != null
        && right != null
        && MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private byte[] mac(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException("Protected invitation HMAC unavailable");
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Protected invitation approval is unavailable");
  }

  public record FrozenApproval(
      UUID approvalId,
      String requestJson,
      String issuerRealm,
      String issuerSubject,
      String requestDigest) {
    @Override
    public String toString() {
      return "FrozenApproval[scope=redacted]";
    }
  }

  /** Only a verified receiver response can construct this readiness value. */
  public static final class VerifiedApproval {
    private final UUID approvalId;
    private final String scopeDigest;
    private final String requestDigest;
    private final String receiptJson;
    private final String responseJson;

    private VerifiedApproval(
        UUID approvalId,
        String scopeDigest,
        String requestDigest,
        String receiptJson,
        String responseJson) {
      this.approvalId = approvalId;
      this.scopeDigest = scopeDigest;
      this.requestDigest = requestDigest;
      this.receiptJson = receiptJson;
      this.responseJson = responseJson;
    }

    public UUID approvalId() {
      return approvalId;
    }

    public String scopeDigest() {
      return scopeDigest;
    }

    public String requestDigest() {
      return requestDigest;
    }

    public String receiptJson() {
      return receiptJson;
    }

    public String responseJson() {
      return responseJson;
    }

    @Override
    public String toString() {
      return "VerifiedApproval[scope=redacted]";
    }
  }
}
