package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import de.caritas.cob.userservice.api.config.auth.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** HTTP adapter tests use explicit test doubles for the still-gated production broker. */
class KeycloakAccountLifecycleCommandsTest {
  private final byte[] key = new byte[32];
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(key);
  private final RestTemplate http = new RestTemplate();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
  private final TaskActorConfiguration actors = mock(TaskActorConfiguration.class);
  private final TaskActorTokenBroker broker = mock(TaskActorTokenBroker.class);
  private final KeycloakAccountLifecycleCommands transport =
      new KeycloakAccountLifecycleCommands(
          http,
          actors,
          broker,
          codec,
          "https://identity.example.test",
          "synthetic",
          "realm-synthetic");
  private final UUID id = UUID.randomUUID();
  private final ExecutionRef execution = new ExecutionRef(UUID.randomUUID(), 0);
  private final TaskActorCredentials owner =
      new TaskActorCredentials(
          TaskActor.ACCOUNT_CREATE,
          "backend-account-create",
          "public-test-secret",
          "11111111-1111-4111-8111-111111111111");
  private final CreationScope scope =
      new CreationScope(
          "7",
          "ASKER",
          "/ordinary/asker",
          new Origin(
              "REGISTRATION",
              "workflow",
              "registration-agency:12",
              null,
              null,
              null,
              List.of("12")));
  private final AccountCreation account =
      new AccountCreation("syntheticä", null, null, null, "de", "synthetic-pāssword", false);

  @Test
  void ordinaryCreationUsesTheCheckedCreatorWhileGenericIssuanceStaysClosed() throws Exception {
    credentials();
    var checked = mock(TaskActorTokenBroker.VerifiedProvisioningToken.class);
    when(checked.accessToken()).thenReturn("public-test-checked");
    when(broker.issueCheckedProvisioning(TaskPurpose.ACCOUNT_PROVISIONING)).thenReturn(checked);
    when(broker.issue(TaskPurpose.ACCOUNT_PROVISIONING))
        .thenThrow(new IllegalStateException("Generic native issuance remains closed"));
    var identity = codec.identity(scope, account);
    var request = new CreateRequest(identity, execution, account);
    var receipt =
        new AttemptResult(id, "account", "OPEN", execution, proof(identity, "account"), null, null);
    server
        .expect(requestTo(url()))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("Authorization", "Bearer public-test-checked"))
        .andRespond(withSuccess(codec.canonical(receipt), MediaType.APPLICATION_JSON));
    assertEquals(receipt, assertDoesNotThrow(() -> transport.create(id, request)));
    verify(broker).issueCheckedProvisioning(TaskPurpose.ACCOUNT_PROVISIONING);
    server.verify();
  }

  @Test
  void sendsExactUtf8CanonicalBytesAndVerifiesFirstNativeReceipt() throws Exception {
    credentials();
    var identity = codec.identity(scope, account);
    var request = new CreateRequest(identity, execution, account);
    var receipt =
        new AttemptResult(id, "account", "OPEN", execution, proof(identity, "account"), null, null);
    server
        .expect(requestTo(url()))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(content().bytes(codec.canonical(request).getBytes(StandardCharsets.UTF_8)))
        .andExpect(header("Authorization", "Bearer public-test-bearer"))
        .andRespond(
            withSuccess(
                codec.canonical(receipt).getBytes(StandardCharsets.UTF_8),
                MediaType.APPLICATION_JSON));
    assertEquals(receipt, transport.create(id, request));
    server.verify();
    verify(broker).issueCheckedProvisioning(TaskPurpose.ACCOUNT_PROVISIONING);
  }

  @Test
  void foreignFirstReceiptCannotBeSavedAsCreationProvenance() throws Exception {
    credentials();
    var identity = codec.identity(scope, account);
    var receipt =
        new AttemptResult(
            id,
            "foreign-account",
            "OPEN",
            execution,
            proof(identity, "original-account"),
            null,
            null);
    server
        .expect(requestTo(url()))
        .andRespond(withSuccess(codec.canonical(receipt), MediaType.APPLICATION_JSON));
    assertThrows(
        IllegalArgumentException.class,
        () -> transport.create(id, new CreateRequest(identity, execution, account)));
    server.verify();
  }

  @Test
  void wrongDurableFingerprintNeverSendsHttpOrUsesAnotherActor() {
    var wrong = new CreationIdentity(scope, "a".repeat(64));
    assertThrows(
        IllegalArgumentException.class,
        () -> transport.create(id, new CreateRequest(wrong, execution, account)));
    verifyNoInteractions(actors, broker);
    server.verify();
  }

  @Test
  void malformedUtf8ResponseIsRejectedBeforeJsonCoercion() {
    credentials();
    var identity = codec.identity(scope, account);
    server
        .expect(requestTo(url()))
        .andRespond(withSuccess(new byte[] {(byte) 0xc3, (byte) 0x28}, MediaType.APPLICATION_JSON));
    assertThrows(
        IllegalArgumentException.class,
        () -> transport.create(id, new CreateRequest(identity, execution, account)));
    server.verify();
  }

  @Test
  void onlyExactConfirmedCreateDuplicateCodeCrossesTheTransportBoundary() {
    credentials();
    var identity = codec.identity(scope, account);
    server
        .expect(requestTo(url()))
        .andRespond(
            withStatus(org.springframework.http.HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"USERNAME_NOT_AVAILABLE\"}"));
    var failure =
        assertThrows(
            KeycloakAccountLifecycleCommands.RemoteFailure.class,
            () -> transport.create(id, new CreateRequest(identity, execution, account)));
    assertEquals(409, failure.status());
    assertEquals("USERNAME_NOT_AVAILABLE", failure.code());
    server.verify();
  }

  @Test
  void arbitraryConflictWithExtraFieldsCannotPretendToBeConfirmedDuplicate() {
    credentials();
    var identity = codec.identity(scope, account);
    server
        .expect(requestTo(url()))
        .andRespond(
            withStatus(org.springframework.http.HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"USERNAME_NOT_AVAILABLE\",\"scope\":\"foreign\"}"));
    var failure =
        assertThrows(
            KeycloakAccountLifecycleCommands.RemoteFailure.class,
            () -> transport.create(id, new CreateRequest(identity, execution, account)));
    assertNull(failure.code());
    server.verify();
  }

  private void credentials() {
    when(actors.require(TaskPurpose.ACCOUNT_PROVISIONING)).thenReturn(owner);
    var checked = mock(TaskActorTokenBroker.VerifiedProvisioningToken.class);
    when(checked.accessToken()).thenReturn("public-test-bearer");
    when(broker.issueCheckedProvisioning(TaskPurpose.ACCOUNT_PROVISIONING)).thenReturn(checked);
  }

  private String url() {
    return "https://identity.example.test/realms/synthetic/oriso-account-lifecycle/v1/attempts/"
        + id;
  }

  private String proof(CreationIdentity identity, String accountId) throws Exception {
    var payload =
        new AccountLifecycleCodec.CreationProofPayload(
            1,
            "synthetic",
            "realm-synthetic",
            id,
            accountId,
            owner.clientId(),
            owner.serviceSubject(),
            identity.scope(),
            "group-synthetic",
            codec.digest("initial-roles", List.of("user")),
            identity.creationFingerprint());
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(codec.canonical(payload).getBytes(StandardCharsets.UTF_8));
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return encoded
        + "."
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                mac.doFinal(("creation-proof\n" + encoded).getBytes(StandardCharsets.UTF_8)));
  }
}
