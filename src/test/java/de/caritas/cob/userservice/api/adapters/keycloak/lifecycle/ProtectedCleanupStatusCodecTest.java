package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.AccountCreation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.LocalOutcome;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCancellation.Reason;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Independent Python vectors; both old receipt domains and the new native wrapper are required. */
class ProtectedCleanupStatusCodecTest {
  private final byte[] key = HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
  private final AccountLifecycleCodec json = new AccountLifecycleCodec(key);
  private final JsonNode original = resource("protected-account-v1.json");
  private final JsonNode recoveryVector = resource("protected-account-recovery-v1.json");
  private final JsonNode vector = resource("protected-cleanup-status-v1.json");
  private final Clock clock = Clock.fixed(Instant.parse("2029-01-01T00:00:00Z"), ZoneOffset.UTC);
  private final ProtectedInvitationCodec approvals = new ProtectedInvitationCodec(key, clock,
      original.path("realmName").asText(), original.path("realmId").asText());
  private final ProtectedAccountCodec accounts = new ProtectedAccountCodec(key, clock, approvals,
      original.path("realmName").asText(), original.path("realmId").asText(), original.path("realmIssuer").asText());
  private final ProtectedInvitationRestrictionCodec restrictions = new ProtectedInvitationRestrictionCodec(
      approvals, json, key, Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC),
      original.path("realmId").asText(), original.path("realmIssuer").asText());
  private final ProtectedCleanupStatusCodec codec = new ProtectedCleanupStatusCodec(json, restrictions, accounts);

  @Test
  void exactBoundCompensationRequiresIndependentWrapperOriginalFenceAndNativeTerminal() {
    var frozen = frozen();
    var first = restrictions.verify(frozen, bytes(vector.path("boundRestriction")), true);
    assertTrue(first.cleanupRequired());
    var recovery = recovered(true);
    var checked = assertDoesNotThrow(() -> codec.verify(frozen, first, recovery, local(), bytes(vector.path("bound"))));
    assertNotNull(checked);
    assertEquals(vector.path("bound"), json.read(checked.responseJson(), JsonNode.class));
    assertTrue(first.cleanupRequired());
    assertTrue(checked.toString().contains("redacted"));
    assertThrows(IllegalArgumentException.class, () -> codec.verify(frozen, first, recovery,
        new LocalOutcome(1, UUID.randomUUID(), "f".repeat(64)), bytes(vector.path("bound"))));
  }

  @Test
  void abandonedOldNativeReceiptCannotReplaceNewCompensateDigestAttestation() {
    var frozen = frozen();
    var first = restrictions.verify(frozen, bytes(vector.path("absentRestriction")), true);
    var recovery = recovered(false);
    assertEquals("ABANDONED", recovery.state());
    assertNull(recovery.accountId());
    var checked = assertDoesNotThrow(() -> codec.verify(frozen, first, recovery, local(), bytes(vector.path("absent"))));
    assertNotNull(checked);
    assertThrows(IllegalArgumentException.class, () -> codec.verify(frozen, first, recovery, local(),
        bytes(recoveryVector.path("abandonedResponse"))));
    ObjectNode replay = vector.path("absent").deepCopy();
    replay.put("compensationDigest", json.digest("protected-invitation-payload/v1", recoveryVector.path("recoverBody")));
    resign(replay);
    assertThrows(IllegalArgumentException.class, () -> codec.verify(frozen, first, recovery, local(), bytes(replay)));
  }

  @Test
  void validWrapperMacCannotHideNestedReceiptDriftOrUnknownAndDuplicateFields() {
    var frozen = frozen();
    var first = restrictions.verify(frozen, bytes(vector.path("boundRestriction")), true);
    var recovery = recovered(true);
    assertNotNull(assertDoesNotThrow(() -> codec.verify(frozen, first, recovery, local(), bytes(vector.path("bound")))));
    for (String member : List.of("restriction", "terminal")) {
      ObjectNode changed = vector.path("bound").deepCopy();
      ((ObjectNode) changed.path(member)).put(member.equals("restriction") ? "approvalRequestDigest" : "terminalReceipt", "f".repeat(64));
      resign(changed);
      assertThrows(IllegalArgumentException.class, () -> codec.verify(frozen, first, recovery, local(), bytes(changed)));
    }
    ObjectNode unknown = vector.path("bound").deepCopy(); unknown.put("authority", true); resign(unknown);
    assertThrows(IllegalArgumentException.class, () -> codec.verify(frozen, first, recovery, local(), bytes(unknown)));
    String duplicate = json.canonical(vector.path("bound")).replaceFirst("\\{", "{\"version\":1,");
    assertThrows(IllegalArgumentException.class, () -> codec.verify(frozen, first, recovery, local(), duplicate.getBytes(StandardCharsets.UTF_8)));
    assertThrows(IllegalArgumentException.class, () -> codec.verify(frozen, first, recovery, local(),
        (json.canonical(vector.path("bound")) + " {}").getBytes(StandardCharsets.UTF_8)));
    assertThrows(IllegalArgumentException.class, () -> codec.verify(frozen, first, recovery, local(), new byte[65537]));
  }

  private ProtectedInvitationRestrictionCodec.FrozenCancellation frozen() {
    var av = original.path("approvalVector");
    var approval = approvals.freeze(UUID.fromString(av.path("approvalId").asText()), av.path("request").toString(),
        av.path("issuerRealm").asText(), av.path("issuerSubject").asText());
    return restrictions.freeze(approval, av.path("response").path("scopeDigest").asText(),
        UUID.fromString("ff088602-ad24-41f4-8cbf-5e6bed44f6fa"), Reason.REVOKED);
  }

  private ProtectedAccountCodec.VerifiedRecovery recovered(boolean bound) {
    var av = original.path("approvalVector"); var frozen = frozen().approval();
    var claim = mock(AcceptanceClaim.class);
    when(claim.frozen()).thenReturn(frozen);
    when(claim.approval()).thenReturn(approvals.verifyAuthorized(frozen, av.path("response").toString()));
    when(claim.claimId()).thenReturn(UUID.fromString(original.path("claimId").asText()));
    when(claim.attemptId()).thenReturn(UUID.fromString(original.path("attemptId").asText()));
    when(claim.executionId()).thenReturn(UUID.fromString(original.path("executionId").asText()));
    when(claim.journalVersion()).thenReturn(1L);
    when(claim.registrationDigest()).thenReturn(original.path("registrationDigest").asText());
    var prepared = accounts.prepare(claim, original.path("registration"), json.read(original.path("account").toString(), AccountCreation.class));
    var metadata = accounts.restorePrepared(claim, accounts.checkpointPrepared(prepared));
    return accounts.verifyRecovery(accounts.prepareRecovery(metadata, bound ? 1 : 0,
        UUID.fromString(recoveryVector.path("recoveryId").asText())), bytes(bound ? vector.path("boundRecovery") : recoveryVector.path("abandonedResponse")));
  }

  private LocalOutcome local() { return json.read(original.path("localOutcome").toString(), LocalOutcome.class); }
  private byte[] bytes(JsonNode value) { return json.canonical(value).getBytes(StandardCharsets.UTF_8); }
  private JsonNode resource(String name) {
    try (var in = getClass().getResourceAsStream("/scenario-c/" + name)) { return json.read(new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8), JsonNode.class); }
    catch (java.io.IOException error) { throw new AssertionError(error); }
  }
  private void resign(ObjectNode envelope) {
    try {
      var payload = envelope.deepCopy(); payload.remove("receipt");
      String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(payload));
      Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
      envelope.put("receipt", encoded + "." + HexFormat.of().formatHex(mac.doFinal(("protected-approval-cleanup-status/v1\n" + encoded).getBytes(StandardCharsets.UTF_8))));
    } catch (java.security.GeneralSecurityException error) { throw new AssertionError(error); }
  }
}
