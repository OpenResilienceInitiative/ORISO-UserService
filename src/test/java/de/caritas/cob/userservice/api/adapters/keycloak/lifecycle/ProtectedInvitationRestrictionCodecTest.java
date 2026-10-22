package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCancellation.Reason;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.FrozenApproval;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationRestrictionCodec.FrozenCancellation;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Synthetic receiver-shaped receipts; no native server or identity claim. */
class ProtectedInvitationRestrictionCodecTest {
  private final ObjectMapper nodes = new ObjectMapper();
  private final JsonNode vector = vector();
  private final byte[] key = HexFormat.of().parseHex(vector.path("syntheticPublicKeyHex").asText());
  private final AccountLifecycleCodec json = new AccountLifecycleCodec(key);
  private final Clock clock = Clock.fixed(Instant.parse("2030-01-03T00:00:00Z"), ZoneOffset.UTC);
  private final String realm = vector.path("issuerRealm").asText();
  private final String issuer = "https://auth.example.test/realms/synthetic";
  private final ProtectedInvitationCodec approvals = new ProtectedInvitationCodec(key, clock, "synthetic", realm);
  private final ProtectedInvitationRestrictionCodec codec = new ProtectedInvitationRestrictionCodec(approvals, json, key, clock, realm, issuer);
  private final UUID cancellationId = UUID.fromString("ff088602-ad24-41f4-8cbf-5e6bed44f6fa");
  private final TaskActorCredentials setup = new TaskActorCredentials(TaskActor.SETUP, "backend-setup", "synthetic-only", "4ebf5f9c-a3f4-41fe-8a22-ec1f511d77ed");

  @Test void freezesTheExactAbsentScopeRestrictionWithoutAnAuthorizingReceipt() {
    var frozen = frozen(null);
    JsonNode body = json.read(frozen.bodyJson(), JsonNode.class);
    assertEquals(8, body.size());
    assertEquals(cancellationId.toString(), body.path("cancellationId").asText());
    assertTrue(body.path("scopeDigest").isNull());
    assertEquals(vector.path("request").path("scope").size()+2, body.path("absentScope").size());
    assertEquals(realm, body.path("absentScope").path("originalIssuerRealm").asText());
    assertEquals(approval().issuerSubject(), body.path("absentScope").path("originalIssuerSubject").asText());
    assertEquals(approval().requestDigest(), body.path("approvalRequestDigest").asText());
    assertEquals(frozen, codec.freeze(approval(), null, cancellationId, Reason.REVOKED));
  }

  @Test void aKnownScopeRemainsFrozenEvenWhenNativeStateLaterChanges() {
    var frozen = frozen("a".repeat(64));
    assertEquals("a".repeat(64), json.read(frozen.bodyJson(), JsonNode.class).path("scopeDigest").asText());
    assertEquals(frozen, codec.freeze(approval(), "a".repeat(64), cancellationId, Reason.REVOKED));
  }

  @Test void signsOnlyAnExactReducingSetupCommand() throws Exception {
    String proof = codec.cancelProof(frozen(null), setup);
    assertNotNull(proof);
    String[] parts = proof.split("\\.");
    assertEquals(2, parts.length);
    JsonNode claims = nodes.readTree(Base64.getUrlDecoder().decode(parts[0]));
    assertEquals(15, claims.size());
    assertEquals("protected-invitation.cancel", claims.path("operation").asText());
    assertEquals("oriso-protected-invitations", claims.path("aud").asText());
    assertEquals("INVITE_RESERVATIONS", claims.path("purpose").asText());
    assertEquals("realm/"+realm+"/protected-invitation/"+approval().approvalId(), claims.path("target").asText());
    assertEquals(json.digest("protected-invitation-payload/v1", json.read(frozen(null).bodyJson(), JsonNode.class)), claims.path("payloadDigest").asText());
    assertEquals(60, claims.path("exp").longValue()-claims.path("iat").longValue());
    assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(mac("protected-invitation-proof/v1\n"+parts[0])), parts[1]);
    for (TaskActor actor : TaskActor.values()) if(actor != TaskActor.SETUP) {
      assertThrows(IllegalArgumentException.class, () -> codec.cancelProof(frozen(null), new TaskActorCredentials(actor, setup.clientId(), "synthetic-only", setup.serviceSubject())));
    }
  }

  @Test void acceptsTheExactDirectCancellationAfterTheOriginalIssuanceDeadline() {
    var ack = codec.verify(frozen("a".repeat(64)), bytes(cancelled(false, false)), false);
    assertNotNull(ack);
    assertFalse(ack.cleanupRequired());
    assertThrows(IllegalArgumentException.class, () -> approvals.verifyAuthorized(approval(), vector.path("response").toString()));
  }

  @Test void anAbsentRestrictionIsAnAcknowledgementButNeverAnApproval() {
    ObjectNode result = absentCancellation();
    var ack = codec.verify(frozen(null), bytes(result), false);
    assertNotNull(ack);
    assertFalse(ack.cleanupRequired());
    assertThrows(IllegalArgumentException.class, () -> approvals.verifyAuthorized(approval(), result.toString()));
  }

  @Test void aSignedAbsentRestrictionCannotBorrowAnUnrelatedScopeDigest() {
    ObjectNode result = absentCancellation();
    result.put("scopeDigest", "b".repeat(64));
    ObjectNode transition = (ObjectNode) result.path("transitionReceipt");
    transition.put("scopeDigest", "b".repeat(64));
    sign(transition, "protected-approval-cancel/v1");
    assertThrows(IllegalArgumentException.class, () -> codec.verify(frozen(null),bytes(result),false));
  }

  @Test void statusCanRecognizeAnEarlierHumanCancellationOfTheSameScope() {
    var ack = codec.verify(frozen("a".repeat(64)), bytes(cancelled(true, false)), true);
    assertNotNull(ack); assertFalse(ack.cleanupRequired());
  }

  @Test void expiryIsAcceptedOnlyAsATerminalStatusWithTheOriginalIssuedReceipt() {
    var ack = codec.verify(frozen("a".repeat(64)), bytes(expired()), true);
    assertNotNull(ack); assertFalse(ack.cleanupRequired());
    assertThrows(IllegalArgumentException.class, () -> codec.verify(frozen("a".repeat(64)), bytes(expired()), false));
  }

  @Test void aBoundFenceStillRequiresOwnedAccountCleanupBeforeReservationRelease() {
    var ack = codec.verify(frozen("a".repeat(64)), bytes(cancelled(false, true)), false);
    assertNotNull(ack); assertTrue(ack.cleanupRequired());
  }

  @Test void rejectsMalformedDuplicateCoercedExtraAndTrailingFields() {
    var original = cancelled(false, false);
    ObjectNode extra=original.deepCopy(); extra.put("allowed", true);
    ObjectNode missing=original.deepCopy(); missing.remove("version");
    ObjectNode coerced=original.deepCopy(); coerced.put("version", "0");
    for(String candidate : new String[]{extra.toString(),missing.toString(),coerced.toString(),original+" {}",original.toString().replaceFirst("\\{", "{\"state\":\"CANCELLED\",")})
      assertThrows(IllegalArgumentException.class, () -> codec.verify(frozen("a".repeat(64)), candidate.getBytes(StandardCharsets.UTF_8), false));
  }

  @Test void rejectsIndependentlySignedForeignTupleValues() {
    for(String field : Set.of("realmId","approvalId","workflowId","inviteId","scopeDigest","approvalRequestDigest","authorizationRevision")) {
      ObjectNode result=cancelled(false,false); ObjectNode transition=(ObjectNode)result.path("transitionReceipt");
      if(field.equals("authorizationRevision")) transition.put(field,2);
      else transition.put(field,switch(field){
        case "realmId" -> "other-native-realm";
        case "approvalId", "workflowId" -> "097ea842-b8b1-4d00-91bc-127599e310f5";
        case "inviteId" -> "74";
        default -> "b".repeat(64);
      });
      sign(transition,"protected-approval-cancel/v1");
      assertThrows(IllegalArgumentException.class, () -> codec.verify(frozen("a".repeat(64)),bytes(result),false),field);
    }
  }

  @Test void directAcknowledgementCannotAdoptAnotherCancellationOrActor() {
    for(String field : Set.of("cancellationId","reason","actor","actorSubject","actorClient","actorIssuer")) {
      ObjectNode result=cancelled(false,false); ObjectNode transition=(ObjectNode)result.path("transitionReceipt");
      transition.put(field,switch(field){
        case "cancellationId", "actorSubject" -> "097ea842-b8b1-4d00-91bc-127599e310f5";
        case "reason" -> "SUPERSEDED";
        case "actor" -> "HUMAN";
        case "actorIssuer" -> "https://other.example.test/realms/synthetic";
        default -> "other-client";
      });
      if(field.equals("actor"))transition.put("actorBindingVersion",3);
      sign(transition,"protected-approval-cancel/v1");
      assertThrows(IllegalArgumentException.class, () -> codec.verify(frozen("a".repeat(64)),bytes(result),false),field);
    }
  }

  @Test void expiryWithoutIssuedReceiptOrWithAFutureExpiryCannotReleaseAnything() {
    ObjectNode absent=expired(); absent.putNull("approvalReceipt");
    assertThrows(IllegalArgumentException.class, () -> codec.verify(frozen("a".repeat(64)),bytes(absent),true));
    ObjectNode future=expired(); ObjectNode transition=(ObjectNode)future.path("transitionReceipt"); transition.put("expiredAt","2030-01-04T00:00:00Z"); sign(transition,"protected-approval-expire/v1");
    assertThrows(IllegalArgumentException.class, () -> codec.verify(frozen("a".repeat(64)),bytes(future),true));
  }

  @Test void boundAttemptAndNativeFenceMustAgreeInBothReceiptAndResponse() {
    ObjectNode mismatch=cancelled(false,true); mismatch.put("boundAttemptId",UUID.randomUUID().toString());
    ObjectNode partial=cancelled(false,true); ((ObjectNode)partial.path("transitionReceipt")).remove("fencedExecution"); sign((ObjectNode)partial.path("transitionReceipt"),"protected-approval-cancel/v1");
    ObjectNode zero=cancelled(false,true); ((ObjectNode)zero.path("fencedExecution")).put("generation",0); ((ObjectNode)zero.path("transitionReceipt")).set("fencedExecution",zero.path("fencedExecution").deepCopy()); sign((ObjectNode)zero.path("transitionReceipt"),"protected-approval-cancel/v1");
    for(ObjectNode result : new ObjectNode[]{mismatch,partial,zero}) assertThrows(IllegalArgumentException.class, () -> codec.verify(frozen("a".repeat(64)),bytes(result),false));
  }

  @Test void rejectsDriftedHistoricalApprovalReceiptAndTamperedTransitionMac() {
    ObjectNode drift=cancelled(false,false); ((ObjectNode)drift.path("approvalReceipt")).put("profileVersion",999);
    ObjectNode tampered=cancelled(false,false); ((ObjectNode)tampered.path("transitionReceipt")).put("receipt","invalid");
    for(ObjectNode result : new ObjectNode[]{drift,tampered}) assertThrows(IllegalArgumentException.class, () -> codec.verify(frozen("a".repeat(64)),bytes(result),false));
  }

  @Test void consumedCommittedBoundAndAuthorizedStatesAreNeverRestrictionAcknowledgements() {
    for(String state : Set.of("CONSUMED","COMMITTED","BOUND","AUTHORIZED")) {
      ObjectNode result=cancelled(false,false);result.put("state",state);
      assertThrows(IllegalArgumentException.class, () -> codec.verify(frozen("a".repeat(64)),bytes(result),true));
    }
  }

  @Test void aForgedFrozenBodyCannotReplaceTheSavedCancellation() {
    var original=frozen(null);
    var forged=new FrozenCancellation(original.approval(),original.knownScopeDigest(),original.cancellationId(),original.reason(),"{}");
    assertThrows(IllegalArgumentException.class, () -> codec.cancelProof(forged,setup));
    assertThrows(IllegalArgumentException.class, () -> codec.verify(forged,bytes(cancelled(false,false)),false));
  }

  private FrozenApproval approval() {
    return approvals.freeze(UUID.fromString(vector.path("approvalId").asText()),vector.path("request").toString(),realm,vector.path("issuerSubject").asText());
  }
  private FrozenCancellation frozen(String digest) {
    var result=codec.freeze(approval(),digest,cancellationId,Reason.REVOKED);assertNotNull(result,"Exact cancellation must be freezeable");return result;
  }
  private ObjectNode cancelled(boolean human,boolean bound) {
    ObjectNode result=(ObjectNode)vector.path("response").deepCopy();result.put("state","CANCELLED");
    ObjectNode transition=tuple();transition.put("operation","protected-invitation.cancel");transition.put("cancellationId",human?UUID.randomUUID().toString():cancellationId.toString());transition.put("reason","REVOKED");transition.put("state","CANCELLED");transition.put("actor",human?"HUMAN":"SETUP");transition.put("actorSubject",human?approval().issuerSubject():setup.serviceSubject());transition.put("actorIssuer",issuer);transition.put("actorClient",human?"admin-panel":setup.clientId());
    if(human) transition.put("actorBindingVersion",3);else transition.putNull("actorBindingVersion");
    if(bound){String attempt="0901d68c-c681-448a-b54e-1819b8d5ade5";ObjectNode fence=nodes.createObjectNode();fence.put("executionId","66bc9c52-5c43-4300-981f-d0bf76bd7ccf");fence.put("generation",2);result.put("boundAttemptId",attempt);result.set("fencedExecution",fence);transition.put("boundAttemptId",attempt);transition.set("fencedExecution",fence.deepCopy());}
    sign(transition,"protected-approval-cancel/v1");result.set("transitionReceipt",transition);return result;
  }
  private ObjectNode expired() {
    ObjectNode result=(ObjectNode)vector.path("response").deepCopy();result.put("state","EXPIRED");ObjectNode transition=tuple();transition.put("operation","protected-invitation.expire");transition.put("reason","EXPIRED");transition.put("state","EXPIRED");transition.put("actor","RECEIVER");transition.put("expiryId",UUID.randomUUID().toString());transition.set("validUntil",vector.path("request").path("scope").path("validUntil"));transition.put("expiredAt","2030-01-03T00:00:00Z");sign(transition,"protected-approval-expire/v1");result.set("transitionReceipt",transition);return result;
  }
  private ObjectNode absentCancellation() {
    ObjectNode result=cancelled(false,false);result.putNull("approvalReceipt");
    ObjectNode restriction=nodes.createObjectNode();restriction.set("scope",vector.path("request").path("scope"));restriction.put("realmId",realm);restriction.put("originalIssuerRealm",realm);restriction.put("originalIssuerSubject",approval().issuerSubject());restriction.put("restrictionOnly",true);
    String digest=json.digest("protected-approval-scope/v1",restriction);result.put("scopeDigest",digest);
    ObjectNode transition=(ObjectNode)result.path("transitionReceipt");transition.put("scopeDigest",digest);sign(transition,"protected-approval-cancel/v1");return result;
  }
  private ObjectNode tuple(){ObjectNode value=nodes.createObjectNode();value.put("realmId",realm);value.set("approvalId",vector.path("approvalId"));for(String name:Set.of("workflowId","inviteId","authorizationRevision"))value.set(name,vector.path("request").path("scope").path(name));value.put("scopeDigest","a".repeat(64));value.put("approvalRequestDigest",approval().requestDigest());return value;}
  private void sign(ObjectNode value,String domain){value.remove("receipt");String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(json.canonical(value).getBytes(StandardCharsets.UTF_8));value.put("receipt",encoded+"."+HexFormat.of().formatHex(mac(domain+"\n"+encoded)));}
  private byte[] mac(String value){try{Mac hmac=Mac.getInstance("HmacSHA256");hmac.init(new SecretKeySpec(key,"HmacSHA256"));return hmac.doFinal(value.getBytes(StandardCharsets.UTF_8));}catch(Exception failure){throw new AssertionError(failure);}}
  private byte[] bytes(JsonNode value){return json.canonical(value).getBytes(StandardCharsets.UTF_8);}
  private JsonNode vector(){try(var input=getClass().getResourceAsStream("/scenario-c/protected-approval-v1.json")){assertNotNull(input);return nodes.readTree(input);}catch(java.io.IOException failure){throw new AssertionError(failure);}}
}
