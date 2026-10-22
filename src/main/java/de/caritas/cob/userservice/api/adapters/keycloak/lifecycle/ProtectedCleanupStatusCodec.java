package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.LocalOutcome;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedRecovery;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationRestrictionCodec.FrozenCancellation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationRestrictionCodec.VerifiedRestriction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Independent native compensation attestation; original restriction receipts remain immutable. */
public final class ProtectedCleanupStatusCodec {
  private final AccountLifecycleCodec json;
  private final ProtectedInvitationRestrictionCodec restrictions;
  private final ProtectedAccountCodec accounts;
  private final byte[] key;

  public ProtectedCleanupStatusCodec(
      AccountLifecycleCodec json,
      ProtectedInvitationRestrictionCodec restrictions,
      ProtectedAccountCodec accounts,
      byte[] key) {
    this.json = Objects.requireNonNull(json);
    this.restrictions = Objects.requireNonNull(restrictions);
    this.accounts = Objects.requireNonNull(accounts);
    if (key == null || key.length < 32) throw invalid();
    this.key = key.clone();
  }

  public VerifiedFinalStatus verify(
      FrozenCancellation frozen,
      VerifiedRestriction original,
      VerifiedRecovery recovery,
      LocalOutcome local,
      byte[] response) {
    try {
      if (frozen == null || original == null || recovery == null || local == null) throw invalid();
      JsonNode value = json.read(json.utf8(response), JsonNode.class);
      fields(value, Set.of("version", "restriction", "terminal", "compensationDigest", "receipt"));
      if (!value.path("version").isIntegralNumber()
          || !value.path("version").canConvertToInt()
          || value.path("version").intValue() != 1) throw invalid();
      ObjectNode signed = value.deepCopy();
      signed.remove("receipt");
      String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(signed));
      if (!equal(encoded + "." + signature(encoded), text(value, "receipt", 65536)))
        throw invalid();
      JsonNode body =
          json.read(json.utf8(accounts.compensateBody(recovery, local)), JsonNode.class);
      String digest = text(value, "compensationDigest", 64);
      if (!digest.matches("[0-9a-f]{64}")
          || !equal(json.digest("protected-invitation-payload/v1", body), digest)) throw invalid();
      var first =
          restrictions.verify(
              frozen, original.responseJson().getBytes(StandardCharsets.UTF_8), true);
      JsonNode firstResult = json.read(first.responseJson(), JsonNode.class);
      JsonNode restricted = value.path("restriction");
      restrictions.verify(frozen, bytes(restricted), true);
      if (!recovery.approvalId().toString().equals(restricted.path("approvalId").textValue())
          || restricted.path("version").longValue() < firstResult.path("version").longValue())
        throw invalid();
      ObjectNode sameOriginal = firstResult.deepCopy();
      sameOriginal.set("version", restricted.path("version"));
      if (!sameOriginal.equals(restricted)) throw invalid();
      if (!firstResult.path("boundAttemptId").isNull()) {
        long fence = firstResult.path("fencedExecution").path("generation").longValue();
        if (!recovery.attemptId().toString().equals(firstResult.path("boundAttemptId").textValue())
            || fence < 1
            || fence == Long.MAX_VALUE
            || recovery.generation() != fence + 1
            || "ABANDONED".equals(recovery.state())
            || recovery.accountId() == null
            || recovery.creationProof() == null) throw invalid();
      } else if (!"ABANDONED".equals(recovery.state())
          || recovery.generation() != 1
          || recovery.accountId() != null
          || recovery.creationProof() != null
          || !firstResult.path("fencedExecution").isNull()) throw invalid();
      accounts.verifyCompensated(recovery, local, bytes(value.path("terminal")));
      return new VerifiedFinalStatus(json.canonical(value));
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  private byte[] bytes(JsonNode value) {
    return json.canonical(value).getBytes(StandardCharsets.UTF_8);
  }

  private String signature(String encoded) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of()
          .formatHex(
              mac.doFinal(
                  ("protected-approval-cleanup-status/v1\n" + encoded)
                      .getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException("Protected status HMAC unavailable");
    }
  }

  private static void fields(JsonNode value, Set<String> expected) {
    if (value == null || !value.isObject()) throw invalid();
    Set<String> actual = new HashSet<>();
    value.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) throw invalid();
  }

  private static String text(JsonNode value, String name, int maximum) {
    JsonNode field = value.path(name);
    if (!field.isTextual() || field.textValue().isBlank() || field.textValue().length() > maximum)
      throw invalid();
    return field.textValue();
  }

  private static boolean equal(String left, String right) {
    return left != null
        && right != null
        && MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Protected cleanup status is unavailable");
  }

  public static final class VerifiedFinalStatus {
    private final String responseJson;

    private VerifiedFinalStatus(String responseJson) {
      this.responseJson = responseJson;
    }

    public String responseJson() {
      return responseJson;
    }

    @Override
    public String toString() {
      return "ProtectedFinalCleanupStatus[redacted]";
    }
  }
}
