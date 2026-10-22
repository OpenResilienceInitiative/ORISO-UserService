package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCancellation.Reason;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.FrozenApproval;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Restriction acknowledgements are distinct from permission to issue or activate an account. */
public final class ProtectedInvitationRestrictionCodec {
  private final ProtectedInvitationCodec approvals;
  private final AccountLifecycleCodec json;
  private final byte[] key;
  private final Clock clock;
  private final String realmId;
  private final String issuer;
  private final ObjectMapper nodes = new ObjectMapper();

  public ProtectedInvitationRestrictionCodec(
      ProtectedInvitationCodec approvals,
      AccountLifecycleCodec json,
      byte[] key,
      Clock clock,
      String realmId,
      String issuer) {
    this.approvals = Objects.requireNonNull(approvals);
    this.json = Objects.requireNonNull(json);
    if (key == null
        || key.length < 32
        || realmId == null
        || realmId.isBlank()
        || issuer == null
        || issuer.isBlank()) throw invalid();
    this.key = key.clone();
    this.clock = Objects.requireNonNull(clock);
    this.realmId = realmId;
    this.issuer = issuer;
  }

  public FrozenCancellation freeze(
      FrozenApproval approval, String knownScopeDigest, UUID cancellationId, Reason reason) {
    try {
      JsonNode scope = json.read(approvals.approvalBody(approval), JsonNode.class).path("scope");
      if (!realmId.equals(approval.issuerRealm()) || cancellationId == null || reason == null)
        throw invalid();
      if (knownScopeDigest != null) hex(knownScopeDigest);
      ObjectNode absent = scope.deepCopy();
      absent.put("originalIssuerRealm", approval.issuerRealm());
      absent.put("originalIssuerSubject", approval.issuerSubject());
      ObjectNode body = nodes.createObjectNode();
      body.put("cancellationId", cancellationId.toString());
      body.put("scopeDigest", knownScopeDigest);
      for (String name : Set.of("workflowId", "inviteId", "authorizationRevision"))
        body.set(name, scope.path(name));
      body.put("reason", reason.name());
      body.set("absentScope", absent);
      body.put("approvalRequestDigest", approval.requestDigest());
      return new FrozenCancellation(
          approval, knownScopeDigest, cancellationId, reason, json.canonical(body));
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  String cancellationBody(FrozenCancellation cancellation) {
    checked(cancellation);
    return cancellation.bodyJson();
  }

  public String cancelProof(FrozenCancellation cancellation, TaskActorCredentials actor) {
    JsonNode scope = checked(cancellation);
    if (actor == null
        || actor.actor() != TaskActor.SETUP
        || !scope.path("setupClient").asText().equals(actor.clientId())
        || !scope.path("setupSubject").asText().equals(actor.serviceSubject())) throw invalid();
    long now = clock.instant().getEpochSecond();
    Map<String, Object> claims = new TreeMap<>();
    claims.put("iss", "userservice");
    claims.put("aud", "oriso-protected-invitations");
    claims.put("purpose", "INVITE_RESERVATIONS");
    claims.put("operation", "protected-invitation.cancel");
    claims.put("method", "POST");
    claims.put("actor", "SETUP");
    claims.put("realm", approvals.realmName());
    claims.put(
        "target",
        "realm/" + realmId + "/protected-invitation/" + cancellation.approval().approvalId());
    claims.put("taskClient", actor.clientId());
    claims.put("taskSubject", actor.serviceSubject());
    claims.put(
        "payloadDigest",
        json.digest(
            "protected-invitation-payload/v1", json.read(cancellation.bodyJson(), JsonNode.class)));
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("nonce", UUID.randomUUID().toString().replace("-", ""));
    claims.put("iat", now);
    claims.put("exp", now + 60);
    String encoded = encode(json.canonical(claims));
    return encoded
        + "."
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mac("protected-invitation-proof/v1\n" + encoded));
  }

  /** Fixed reducing status read only; closed until the HTTP counterfactual is measured. */
  public String cleanupStatusProof(FrozenCancellation cancellation, TaskActorCredentials actor) {
    return null;
  }

  public VerifiedRestriction verify(
      FrozenCancellation cancellation, byte[] responseBytes, boolean statusReconciliation) {
    try {
      JsonNode scope = checked(cancellation);
      JsonNode response = json.read(json.utf8(responseBytes), JsonNode.class);
      fields(
          response,
          Set.of(
              "approvalId",
              "scopeDigest",
              "state",
              "version",
              "boundAttemptId",
              "fencedExecution",
              "approvalReceipt",
              "transitionReceipt",
              "approvalRequestDigest"));
      var approval = cancellation.approval();
      String digest = hex(text(response, "scopeDigest", 64));
      String state = text(response, "state", 20);
      if (!approval.approvalId().toString().equals(text(response, "approvalId", 36))
          || !equal(approval.requestDigest(), text(response, "approvalRequestDigest", 64))
          || number(response, "version") < 0
          || !Set.of("CANCELLED", "EXPIRED").contains(state)
          || cancellation.knownScopeDigest() != null
              && !equal(cancellation.knownScopeDigest(), digest)) throw invalid();
      boolean bound = !response.path("boundAttemptId").isNull();
      JsonNode transition = response.path("transitionReceipt");
      Set<String> names =
          new HashSet<>(
              Set.of(
                  "realmId",
                  "approvalId",
                  "workflowId",
                  "inviteId",
                  "authorizationRevision",
                  "scopeDigest",
                  "approvalRequestDigest",
                  "operation",
                  "reason",
                  "state",
                  "actor",
                  "receipt"));
      if ("CANCELLED".equals(state))
        names.addAll(
            Set.of(
                "cancellationId",
                "actorSubject",
                "actorIssuer",
                "actorClient",
                "actorBindingVersion"));
      else names.addAll(Set.of("expiryId", "validUntil", "expiredAt"));
      if (bound) names.addAll(Set.of("boundAttemptId", "fencedExecution"));
      fields(transition, names);
      if (!realmId.equals(text(transition, "realmId", 255))
          || !approval.approvalId().toString().equals(text(transition, "approvalId", 36))
          || !equal(digest, text(transition, "scopeDigest", 64))
          || !equal(approval.requestDigest(), text(transition, "approvalRequestDigest", 64))
          || !scope.path("workflowId").equals(transition.path("workflowId"))
          || !scope.path("inviteId").equals(transition.path("inviteId"))
          || !scope.path("authorizationRevision").equals(transition.path("authorizationRevision"))
          || !state.equals(text(transition, "state", 20))) throw invalid();
      checkFence(response, transition, bound);
      if (!response.path("approvalReceipt").isNull())
        checkHistoricalApproval(approval, scope, digest, response.path("approvalReceipt"));
      else {
        if (!"CANCELLED".equals(state) || bound || cancellation.knownScopeDigest() != null)
          throw invalid();
        ObjectNode restriction = nodes.createObjectNode();
        restriction.set("scope", scope);
        restriction.put("realmId", realmId);
        restriction.put("originalIssuerRealm", approval.issuerRealm());
        restriction.put("originalIssuerSubject", approval.issuerSubject());
        restriction.put("restrictionOnly", true);
        if (!equal(digest, json.digest("protected-approval-scope/v1", restriction)))
          throw invalid();
      }
      if ("CANCELLED".equals(state))
        checkCancellation(cancellation, scope, transition, statusReconciliation);
      else checkExpiry(scope, transition, statusReconciliation);
      verifyReceipt(
          transition,
          "CANCELLED".equals(state)
              ? "protected-approval-cancel/v1"
              : "protected-approval-expire/v1");
      return new VerifiedRestriction(json.canonical(response), bound);
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  private void checkCancellation(
      FrozenCancellation frozen, JsonNode scope, JsonNode receipt, boolean status) {
    if (!"protected-invitation.cancel".equals(text(receipt, "operation", 80))) throw invalid();
    String id = uuid(text(receipt, "cancellationId", 36));
    String reason = text(receipt, "reason", 30);
    Reason.valueOf(reason);
    if (!status
        && (!frozen.cancellationId().toString().equals(id)
            || !frozen.reason().name().equals(reason))) throw invalid();
    String actor = text(receipt, "actor", 20);
    uuid(text(receipt, "actorSubject", 36));
    if (!issuer.equals(text(receipt, "actorIssuer", 512))) throw invalid();
    text(receipt, "actorClient", 255);
    if ("SETUP".equals(actor)) {
      if (!scope.path("setupSubject").asText().equals(receipt.path("actorSubject").asText())
          || !scope.path("setupClient").asText().equals(receipt.path("actorClient").asText())
          || !receipt.path("actorBindingVersion").isNull()) throw invalid();
    } else if (!status || !"HUMAN".equals(actor) || number(receipt, "actorBindingVersion") <= 0)
      throw invalid();
  }

  private void checkExpiry(JsonNode scope, JsonNode receipt, boolean status) {
    if (!status
        || !"protected-invitation.expire".equals(text(receipt, "operation", 80))
        || !"EXPIRED".equals(text(receipt, "reason", 30))
        || !"RECEIVER".equals(text(receipt, "actor", 20))
        || !scope.path("validUntil").equals(receipt.path("validUntil"))) throw invalid();
    uuid(text(receipt, "expiryId", 36));
    Instant deadline = instant(text(receipt, "validUntil", 30));
    Instant expiredAt = instant(text(receipt, "expiredAt", 30));
    if (deadline.isAfter(expiredAt) || expiredAt.isAfter(clock.instant())) throw invalid();
  }

  private void checkHistoricalApproval(
      FrozenApproval approval, JsonNode scope, String digest, JsonNode receipt) {
    // Pure historical authenticity only. This method never returns an invitation-ready value.
    fields(
        receipt,
        Set.of(
            "approvalId",
            "scopeDigest",
            "issuerSubject",
            "bindingVersion",
            "profileVersion",
            "validUntil",
            "approvalRequestDigest",
            "receipt"));
    if (!approval.approvalId().toString().equals(text(receipt, "approvalId", 36))
        || !equal(digest, text(receipt, "scopeDigest", 64))
        || !approval.issuerSubject().equals(text(receipt, "issuerSubject", 36))
        || !equal(approval.requestDigest(), text(receipt, "approvalRequestDigest", 64))
        || !scope.path("validUntil").equals(receipt.path("validUntil"))
        || number(receipt, "bindingVersion") <= 0
        || number(receipt, "profileVersion") <= 0) throw invalid();
    instant(text(receipt, "validUntil", 30));
    verifyReceipt(receipt, "protected-approval-receipt/v1");
  }

  private void checkFence(JsonNode response, JsonNode transition, boolean bound) {
    if (!bound) {
      if (!response.path("fencedExecution").isNull()) throw invalid();
      return;
    }
    String attempt = uuid(text(response, "boundAttemptId", 36));
    if (!attempt.equals(uuid(text(transition, "boundAttemptId", 36)))
        || !response.path("fencedExecution").equals(transition.path("fencedExecution")))
      throw invalid();
    JsonNode fence = response.path("fencedExecution");
    fields(fence, Set.of("executionId", "generation"));
    uuid(text(fence, "executionId", 36));
    if (number(fence, "generation") <= 0) throw invalid();
  }

  private void verifyReceipt(JsonNode receipt, String domain) {
    ObjectNode payload = receipt.deepCopy();
    payload.remove("receipt");
    String encoded = encode(json.canonical(payload));
    if (!equal(
        encoded + "." + HexFormat.of().formatHex(mac(domain + "\n" + encoded)),
        text(receipt, "receipt", 24000))) throw invalid();
  }

  private JsonNode checked(FrozenCancellation saved) {
    if (saved == null) throw invalid();
    FrozenCancellation checked =
        freeze(saved.approval(), saved.knownScopeDigest(), saved.cancellationId(), saved.reason());
    if (!checked.equals(saved)) throw invalid();
    return json.read(approvals.approvalBody(saved.approval()), JsonNode.class).path("scope");
  }

  private static void fields(JsonNode value, Set<String> expected) {
    if (value == null || !value.isObject()) throw invalid();
    Set<String> actual = new HashSet<>();
    value.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) throw invalid();
  }

  private static String text(JsonNode value, String name, int max) {
    JsonNode item = value.path(name);
    if (!item.isTextual()
        || item.textValue().isBlank()
        || item.textValue().length() > max
        || item.textValue().chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
    return item.textValue();
  }

  private static long number(JsonNode value, String name) {
    JsonNode item = value.path(name);
    if (!item.isIntegralNumber() || !item.canConvertToLong()) throw invalid();
    return item.longValue();
  }

  private static String uuid(String value) {
    if (!UUID.fromString(value).toString().equals(value)) throw invalid();
    return value;
  }

  private static String hex(String value) {
    if (!value.matches("[0-9a-f]{64}")) throw invalid();
    return value;
  }

  private static Instant instant(String value) {
    Instant result = Instant.parse(value);
    if (!result.toString().equals(value)) throw invalid();
    return result;
  }

  private static boolean equal(String a, String b) {
    return a != null
        && b != null
        && MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  private static String encode(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private byte[] mac(String value) {
    try {
      Mac result = Mac.getInstance("HmacSHA256");
      result.init(new SecretKeySpec(key, "HmacSHA256"));
      return result.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException("Protected restriction HMAC unavailable");
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Protected invitation restriction is unavailable");
  }

  public record FrozenCancellation(
      FrozenApproval approval,
      String knownScopeDigest,
      UUID cancellationId,
      Reason reason,
      String bodyJson) {
    @Override
    public String toString() {
      return "FrozenCancellation[scope=redacted]";
    }
  }

  public static final class VerifiedRestriction {
    private final String responseJson;
    private final boolean cleanupRequired;

    private VerifiedRestriction(String responseJson, boolean cleanupRequired) {
      this.responseJson = responseJson;
      this.cleanupRequired = cleanupRequired;
    }

    public String responseJson() {
      return responseJson;
    }

    public boolean cleanupRequired() {
      return cleanupRequired;
    }

    @Override
    public String toString() {
      return "VerifiedRestriction[scope=redacted]";
    }
  }
}
