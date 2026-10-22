package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCancellation.Reason;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationRestrictionCodec.FrozenCancellation;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

/** Actual HTTP encoding plus real restriction MAC verification; external receiver is a test double. */
class KeycloakProtectedRestrictionsTest {
  private final JsonNode vector = vector("/scenario-c/protected-approval-v1.json");
  private final JsonNode terminals = vector("/scenario-c/protected-restriction-v1.json");
  private final byte[] key = HexFormat.of().parseHex(vector.path("syntheticPublicKeyHex").asText());
  private final AccountLifecycleCodec json = new AccountLifecycleCodec(key);
  private final Clock clock = Clock.fixed(Instant.parse("2030-01-03T00:00:00Z"), ZoneOffset.UTC);
  private final ProtectedInvitationCodec approvals = new ProtectedInvitationCodec(key, clock, "synthetic", vector.path("issuerRealm").asText());
  private final ProtectedInvitationRestrictionCodec restrictions = new ProtectedInvitationRestrictionCodec(approvals, json, key, clock, vector.path("issuerRealm").asText(), "https://auth.example.test/realms/synthetic");
  private final RestTemplate http = new RestTemplate();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
  private final TaskActorConfiguration actors = mock(TaskActorConfiguration.class);
  private final TaskActorTokenBroker broker = mock(TaskActorTokenBroker.class);

  @BeforeEach void actor() {
    lenient().when(actors.require(TaskPurpose.INVITE_RESERVATIONS)).thenReturn(new TaskActorCredentials(TaskActor.SETUP,"backend-setup","synthetic-only","4ebf5f9c-a3f4-41fe-8a22-ec1f511d77ed"));
    lenient().when(broker.issue(TaskPurpose.INVITE_RESERVATIONS)).thenReturn(new TaskActorTokenBroker.TaskToken(TaskActor.SETUP,"public-synthetic-setup",60));
  }

  @Test void sendsOnlyTheExactFrozenUtf8CancellationUnderSetupAuthority() {
    var frozen=frozen();
    server.expect(requestTo(url()+"/cancel")).andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization","Bearer public-synthetic-setup"))
        .andExpect(content().bytes(frozen.bodyJson().getBytes(StandardCharsets.UTF_8)))
        .andExpect(request -> assertOperation(request.getHeaders().getFirst("X-ORISO-Task-Context"),"protected-invitation.cancel",frozen.bodyJson()))
        .andRespond(withSuccess(terminals.path("cancelledIssued").toString(),MediaType.APPLICATION_JSON));
    var ack=transport().cancel(frozen);
    assertNotNull(ack);assertFalse(ack.cleanupRequired());server.verify();
    verify(broker).issue(TaskPurpose.INVITE_RESERVATIONS);
  }

  @Test void aLostCancelReplyRecoversTheSameScopeWithoutReplayingCancelOrHumanApproval() {
    var frozen=frozen();
    server.expect(requestTo(url()+"/cancel")).andRespond(request -> {throw new IOException("synthetic lost acknowledgement");});
    server.expect(requestTo(url()+"/status")).andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization","Bearer public-synthetic-setup"))
        .andExpect(content().bytes(approvals.statusBody(frozen.approval()).getBytes(StandardCharsets.UTF_8)))
        .andExpect(request -> assertOperation(request.getHeaders().getFirst("X-ORISO-Task-Context"),"protected-invitation.status",approvals.statusBody(frozen.approval())))
        .andRespond(withSuccess(terminals.path("cancelledIssued").toString(),MediaType.APPLICATION_JSON));
    var unknown=assertThrows(KeycloakProtectedInvitations.RemoteFailure.class,()->transport().cancel(frozen));
    assertEquals(0,unknown.status());
    var ack=transport().reconcile(frozen);
    assertNotNull(ack);assertFalse(ack.cleanupRequired());server.verify();
    verify(broker,times(2)).issue(TaskPurpose.INVITE_RESERVATIONS);
  }

  @Test void expired409RequiresAnExplicitStatusReadBeforeAnyTerminalAcknowledgement() {
    server.expect(requestTo(url()+"/cancel")).andRespond(withStatus(HttpStatus.CONFLICT));
    server.expect(requestTo(url()+"/status")).andRespond(withSuccess(terminals.path("expired").toString(),MediaType.APPLICATION_JSON));
    var refused=assertThrows(KeycloakProtectedInvitations.RemoteFailure.class,()->transport().cancel(frozen()));
    assertEquals(409,refused.status());
    assertNotNull(transport().reconcile(frozen()));server.verify();
  }

  @Test void native404IsNotAnAcknowledgementAndNeverTriggersAnAutomaticRetry() {
    server.expect(requestTo(url()+"/status")).andRespond(withStatus(HttpStatus.NOT_FOUND));
    var failure=assertThrows(KeycloakProtectedInvitations.RemoteFailure.class,()->transport().reconcile(frozen()));
    assertEquals(404,failure.status());server.verify();verify(broker).issue(TaskPurpose.INVITE_RESERVATIONS);
  }

  @Test void malformedOrForeign200CannotAcknowledgeTheSavedCancellation() {
    var result=(ObjectNode)terminals.path("cancelledIssued").deepCopy();result.put("approvalId",UUID.randomUUID().toString());
    server.expect(requestTo(url()+"/cancel")).andRespond(withSuccess(result.toString(),MediaType.APPLICATION_JSON));
    var failure=assertThrows(KeycloakProtectedInvitations.RemoteFailure.class,()->transport().cancel(frozen()));
    assertEquals(0,failure.status());assertNull(failure.getCause());server.verify();
  }

  @Test void accepted202IsStillUnknownInsteadOfACompletedRestriction() {
    server.expect(requestTo(url()+"/cancel")).andRespond(withStatus(HttpStatus.ACCEPTED).body(terminals.path("cancelledIssued").toString()));
    var failure=assertThrows(KeycloakProtectedInvitations.RemoteFailure.class,()->transport().cancel(frozen()));
    assertEquals(0,failure.status());server.verify();
  }

  @Test void remoteCommandsNeverRunWhileOwningLocalSqlIsOpen() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThrows(IllegalStateException.class,()->transport().cancel(frozen()));
      assertThrows(IllegalStateException.class,()->transport().reconcile(frozen()));
    } finally {TransactionSynchronizationManager.setActualTransactionActive(false);}
    verifyNoInteractions(actors,broker);server.verify();
  }

  @Test void providerBodyAndCredentialsNeverEscapeThroughFailureOrCause() {
    server.expect(requestTo(url()+"/cancel")).andRespond(withStatus(HttpStatus.FORBIDDEN).body("private-synthetic-provider-body"));
    var failure=assertThrows(KeycloakProtectedInvitations.RemoteFailure.class,()->transport().cancel(frozen()));
    assertEquals(403,failure.status());assertNull(failure.getCause());
    assertFalse(failure.getMessage().contains("private-synthetic-provider-body"));
    assertFalse(failure.getMessage().contains("public-synthetic-setup"));server.verify();
  }

  @Test void aChangedFrozenBodyIsRejectedBeforeEitherReducingRouteCanBeCalled() {
    var valid=frozen();var changed=new FrozenCancellation(valid.approval(),valid.knownScopeDigest(),valid.cancellationId(),valid.reason(),"{}");
    assertThrows(IllegalArgumentException.class,()->transport().cancel(changed));
    assertThrows(IllegalArgumentException.class,()->transport().reconcile(changed));
    verifyNoInteractions(actors,broker);server.verify();
  }

  private FrozenCancellation frozen(){var proposal=approvals.freeze(UUID.fromString(vector.path("approvalId").asText()),vector.path("request").toString(),vector.path("issuerRealm").asText(),vector.path("issuerSubject").asText());return restrictions.freeze(proposal,"a".repeat(64),UUID.fromString("ff088602-ad24-41f4-8cbf-5e6bed44f6fa"),Reason.REVOKED);}
  private KeycloakProtectedRestrictions transport(){return new KeycloakProtectedRestrictions(http,actors,broker,approvals,restrictions,"https://identity.example.test","synthetic");}
  private String url(){return "https://identity.example.test/realms/synthetic/oriso-protected-invitations/v1/approvals/"+vector.path("approvalId").asText();}
  private void assertOperation(String proof,String operation,String body){
    assertNotNull(proof);String[] parts=proof.split("\\.",-1);assertEquals(2,parts.length);
    JsonNode claims=json.read(new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8),JsonNode.class);
    Set<String> fields=new HashSet<>();claims.fieldNames().forEachRemaining(fields::add);
    assertEquals(Set.of("iss","aud","purpose","operation","method","actor","realm","target","taskClient","taskSubject","payloadDigest","jti","nonce","iat","exp"),fields);
    assertEquals("userservice",claims.path("iss").asText());assertEquals(operation,claims.path("operation").asText());
    assertEquals("INVITE_RESERVATIONS",claims.path("purpose").asText());assertEquals("POST",claims.path("method").asText());
    assertEquals("SETUP",claims.path("actor").asText());assertEquals("oriso-protected-invitations",claims.path("aud").asText());
    assertEquals("synthetic",claims.path("realm").asText());
    assertEquals("realm/"+vector.path("issuerRealm").asText()+"/protected-invitation/"+vector.path("approvalId").asText(),claims.path("target").asText());
    assertEquals("backend-setup",claims.path("taskClient").asText());assertEquals("4ebf5f9c-a3f4-41fe-8a22-ec1f511d77ed",claims.path("taskSubject").asText());
    assertEquals(json.digest("protected-invitation-payload/v1",json.read(body,JsonNode.class)),claims.path("payloadDigest").asText());
    assertEquals(UUID.fromString(claims.path("jti").asText()).toString(),claims.path("jti").asText());
    assertTrue(claims.path("nonce").asText().matches("[0-9a-f]{32}"));
    assertTrue(claims.path("iat").isIntegralNumber());assertEquals(clock.instant().getEpochSecond(),claims.path("iat").longValue());
    assertTrue(claims.path("exp").isIntegralNumber());assertEquals(clock.instant().getEpochSecond()+60,claims.path("exp").longValue());
    try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));assertArrayEquals(mac.doFinal(("protected-invitation-proof/v1\n"+parts[0]).getBytes(StandardCharsets.UTF_8)),Base64.getUrlDecoder().decode(parts[1]));}catch(java.security.GeneralSecurityException failure){throw new AssertionError(failure);}
  }
  private static JsonNode vector(String name){try(var input=KeycloakProtectedRestrictionsTest.class.getResourceAsStream(name)){assertNotNull(input);return new ObjectMapper().readTree(input);}catch(IOException failure){throw new AssertionError(failure);}}
}
