package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.LocalOutcome;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedAccountActivation.ReadyActivation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.PreparedCreation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedCommitted;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedCreated;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

/** Actual HTTP and handoff sequencing; crypto input handles are explicit codec doubles here. */
class KeycloakProtectedAccountsTest {
  private static final UUID APPROVAL = UUID.fromString("7f56c348-9785-4c78-b905-3d008dd42482");
  private static final UUID CLAIM = UUID.fromString("80d89227-917d-40cd-bf23-0634f8bb055d");
  private static final UUID ATTEMPT = UUID.fromString("39fe99aa-fd8e-44c8-9b48-71ea6cc09d38");
  private static final UUID EXECUTION = UUID.fromString("025ef690-ddbb-463f-9118-c226c440cb9d");
  private final RestTemplate http = new RestTemplate();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
  private final TaskActorConfiguration actors = mock(TaskActorConfiguration.class);
  private final TaskActorTokenBroker broker = mock(TaskActorTokenBroker.class);
  private final ProtectedAccountCodec codec = mock(ProtectedAccountCodec.class);
  private final PreparedCreation prepared = mock(PreparedCreation.class);
  private final VerifiedCreated created = mock(VerifiedCreated.class);
  private final AcceptanceClaim claim = mock(AcceptanceClaim.class);
  private final byte[] createBody =
      "{\"synthetic\":\"create-memory-only\"}".getBytes(StandardCharsets.UTF_8);
  private final byte[] createdResponse =
      "{\"synthetic\":\"created\"}".getBytes(StandardCharsets.UTF_8);
  private final byte[] readBody = "{\"synthetic\":\"owned-read\"}".getBytes(StandardCharsets.UTF_8);
  private final byte[] projection =
      "{\"username\":\"enc.synthetic-owned\"}".getBytes(StandardCharsets.UTF_8);
  private KeycloakProtectedAccounts transport;

  @BeforeEach
  void bindings() {
    lenient().when(codec.realmName()).thenReturn("oriso");
    lenient()
        .when(actors.require(TaskPurpose.ACCOUNT_PROVISIONING))
        .thenReturn(
            new TaskActorCredentials(
                TaskActor.ACCOUNT_CREATE,
                "backend-account-create",
                "synthetic-only",
                "c6c2db59-43dc-49ad-8589-16e91673ce2c"));
    var checkedCreator = mock(TaskActorTokenBroker.VerifiedProvisioningToken.class);
    lenient().when(checkedCreator.actor()).thenReturn(TaskActor.ACCOUNT_CREATE);
    lenient().when(checkedCreator.accessToken()).thenReturn("public-synthetic-creator");
    lenient().when(broker.issueCheckedProvisioning(TaskPurpose.ACCOUNT_PROVISIONING)).thenReturn(checkedCreator);
    lenient().when(prepared.approvalId()).thenReturn(APPROVAL);
    lenient().when(prepared.claimId()).thenReturn(CLAIM);
    lenient().when(prepared.attemptId()).thenReturn(ATTEMPT);
    lenient().when(prepared.executionId()).thenReturn(EXECUTION);
    lenient().when(created.approvalId()).thenReturn(APPROVAL);
    lenient().when(created.realmId()).thenReturn("synthetic-realm");
    lenient().when(created.claimId()).thenReturn(CLAIM);
    lenient().when(created.attemptId()).thenReturn(ATTEMPT);
    lenient().when(created.executionId()).thenReturn(EXECUTION);
    lenient().when(claim.claimId()).thenReturn(CLAIM);
    lenient().when(claim.attemptId()).thenReturn(ATTEMPT);
    lenient().when(claim.executionId()).thenReturn(EXECUTION);
    lenient()
        .when(claim.frozen())
        .thenReturn(
            new ProtectedInvitationCodec.FrozenApproval(
                APPROVAL, "{}", "synthetic-realm", "synthetic-owner", "c".repeat(64)));
    lenient().when(codec.createBody(prepared)).thenReturn(createBody);
    lenient().when(codec.ownedReadBody(created)).thenReturn(readBody);
    lenient()
        .when(codec.createProof(eq(prepared), any()))
        .thenReturn("public-synthetic-create-proof");
    lenient()
        .when(codec.ownedReadProof(eq(created), any()))
        .thenReturn("public-synthetic-read-proof");
    lenient().when(codec.verifyCreated(eq(prepared), aryEq(createdResponse))).thenReturn(created);
    transport =
        new KeycloakProtectedAccounts(
            http, actors, broker, codec, "https://auth.example.test", "oriso");
    clearInvocations(actors, broker, codec);
  }

  @Test
  void protectedCreationUsesTheCheckedCreatorWhileGenericIssuanceStaysClosed() {
    var checked = mock(TaskActorTokenBroker.VerifiedProvisioningToken.class);
    when(checked.actor()).thenReturn(TaskActor.ACCOUNT_CREATE);
    when(checked.accessToken()).thenReturn("public-synthetic-checked");
    when(broker.issueCheckedProvisioning(TaskPurpose.ACCOUNT_PROVISIONING)).thenReturn(checked);
    when(broker.issue(TaskPurpose.ACCOUNT_PROVISIONING))
        .thenThrow(new IllegalStateException("Generic native issuance remains closed"));
    server
        .expect(requestTo(url()))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("Authorization", "Bearer public-synthetic-checked"))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .body(createdResponse)
                .contentType(MediaType.APPLICATION_JSON));
    assertSame(created, assertDoesNotThrow(() -> transport.create(prepared)));
    verify(broker).issueCheckedProvisioning(TaskPurpose.ACCOUNT_PROVISIONING);
    server.verify();
  }

  @Test
  void createUsesOnlyTheFixedCreatorRouteAndExactMemoryBody() {
    server
        .expect(requestTo(url()))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("Authorization", "Bearer public-synthetic-creator"))
        .andExpect(header("X-ORISO-Task-Context", "public-synthetic-create-proof"))
        .andExpect(content().bytes(createBody))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .body(createdResponse)
                .contentType(MediaType.APPLICATION_JSON));
    assertSame(created, assertDoesNotThrow(() -> transport.create(prepared)));
    verify(codec).verifyCreated(eq(prepared), aryEq(createdResponse));
    server.verify();
  }

  @Test
  void onlyAnActualSuccessfulOwnedReadCanMintThePendingHandoff() {
    server
        .expect(requestTo(url() + "/owned-read"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer public-synthetic-creator"))
        .andExpect(header("X-ORISO-Task-Context", "public-synthetic-read-proof"))
        .andExpect(content().bytes(readBody))
        .andRespond(withSuccess(projection, MediaType.APPLICATION_JSON));
    var pending = assertDoesNotThrow(() -> transport.ownedRead(claim, created));
    assertNotNull(pending);
    assertSame(claim, pending.claim());
    assertSame(created, pending.created());
    assertEquals("oriso", pending.realmName());
    assertEquals("synthetic-realm", pending.realmId());
    assertEquals("enc.synthetic-owned", pending.username());
    verify(codec).checkOwnedProjection(eq(created), aryEq(projection));
    assertEquals("ProtectedPendingOwnedAccount[redacted]", pending.toString());
    server.verify();
  }

  @Test
  void activationRequiresTheCommittedPrivateLocalHandleAndExactNativeReceipt() {
    var ready = mock(ReadyActivation.class);
    var local =
        new LocalOutcome(
            1, UUID.fromString("742ae66e-2fe1-44d4-998c-d3543e5a1bc8"), "a".repeat(64));
    when(ready.created()).thenReturn(created);
    when(ready.outcome()).thenReturn(local);
    byte[] commitBody =
        "{\"synthetic\":\"committed-local-outcome\"}".getBytes(StandardCharsets.UTF_8);
    when(codec.commitBody(created, local)).thenReturn(commitBody);
    when(codec.commitProof(eq(created), eq(local), any()))
        .thenReturn("public-synthetic-commit-proof");
    var committed = mock(VerifiedCommitted.class);
    when(codec.verifyCommitted(eq(created), eq(local), aryEq(createdResponse)))
        .thenReturn(committed);
    server
        .expect(requestTo(url() + "/commit"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer public-synthetic-creator"))
        .andExpect(header("X-ORISO-Task-Context", "public-synthetic-commit-proof"))
        .andExpect(content().bytes(commitBody))
        .andRespond(withSuccess(createdResponse, MediaType.APPLICATION_JSON));
    assertSame(committed, assertDoesNotThrow(() -> transport.commit(ready)));
    verify(codec).verifyCommitted(eq(created), eq(local), aryEq(createdResponse));
    server.verify();
  }

  @Test
  void anUnconfirmedCommitCannotFinishTheLocalActivation() {
    var ready = mock(ReadyActivation.class);
    var local =
        new LocalOutcome(
            1, UUID.fromString("742ae66e-2fe1-44d4-998c-d3543e5a1bc8"), "a".repeat(64));
    when(ready.created()).thenReturn(created);
    when(ready.outcome()).thenReturn(local);
    when(codec.commitBody(created, local)).thenReturn(createBody);
    when(codec.commitProof(eq(created), eq(local), any()))
        .thenReturn("public-synthetic-commit-proof");
    server
        .expect(requestTo(url() + "/commit"))
        .andRespond(withStatus(HttpStatus.ACCEPTED).body(createdResponse));
    assertThrows(KeycloakProtectedAccounts.RemoteFailure.class, () -> transport.commit(ready));
    verify(codec, never()).verifyCommitted(any(), any(), any());
    server.verify();
  }

  @Test
  void aCreationReceiptCannotBeReboundToAnotherDurableClaim() {
    var original = claim.frozen();
    assertAll(
        () ->
            refusesTuple(
                () -> when(claim.claimId()).thenReturn(UUID.randomUUID()),
                () -> when(claim.claimId()).thenReturn(CLAIM)),
        () ->
            refusesTuple(
                () -> when(claim.attemptId()).thenReturn(UUID.randomUUID()),
                () -> when(claim.attemptId()).thenReturn(ATTEMPT)),
        () ->
            refusesTuple(
                () -> when(claim.executionId()).thenReturn(UUID.randomUUID()),
                () -> when(claim.executionId()).thenReturn(EXECUTION)),
        () ->
            refusesTuple(
                () ->
                    when(claim.frozen())
                        .thenReturn(
                            new ProtectedInvitationCodec.FrozenApproval(
                                UUID.randomUUID(),
                                "{}",
                                "synthetic-realm",
                                "synthetic-owner",
                                "c".repeat(64))),
                () -> when(claim.frozen()).thenReturn(original)),
        () ->
            refusesTuple(
                () ->
                    when(claim.frozen())
                        .thenReturn(
                            new ProtectedInvitationCodec.FrozenApproval(
                                APPROVAL,
                                "{}",
                                "other-synthetic-realm",
                                "synthetic-owner",
                                "c".repeat(64))),
                () -> when(claim.frozen()).thenReturn(original)));
  }

  @Test
  void noReceiverRequestMayRunInsideAnAmbientLocalTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThrows(IllegalStateException.class, () -> transport.create(prepared));
      assertThrows(IllegalStateException.class, () -> transport.ownedRead(claim, created));
      assertThrows(
          IllegalStateException.class, () -> transport.commit(mock(ReadyActivation.class)));
      verifyNoInteractions(actors, broker, codec);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  @Test
  void anAcceptedButUnconfirmedCreateNeverReturnsOwnership() {
    server
        .expect(requestTo(url()))
        .andRespond(withStatus(HttpStatus.ACCEPTED).body(createdResponse));
    assertThrows(KeycloakProtectedAccounts.RemoteFailure.class, () -> transport.create(prepared));
    verify(codec, never()).verifyCreated(any(), any());
    server.verify();
  }

  @Test
  void aRejectedProjectionCannotProduceAnOwnedPendingHandle() {
    doThrow(new IllegalArgumentException("synthetic malformed native projection"))
        .when(codec)
        .checkOwnedProjection(eq(created), aryEq(projection));
    server
        .expect(requestTo(url() + "/owned-read"))
        .andRespond(withSuccess(projection, MediaType.APPLICATION_JSON));
    assertThrows(
        KeycloakProtectedAccounts.RemoteFailure.class, () -> transport.ownedRead(claim, created));
    verify(codec).checkOwnedProjection(eq(created), aryEq(projection));
    server.verify();
  }

  @Test
  void providerErrorsExposeOnlyStatusWithoutTheirBodyOrCause() {
    server
        .expect(requestTo(url()))
        .andRespond(
            withStatus(HttpStatus.FORBIDDEN)
                .body("synthetic-password-or-bearer-should-never-escape"));
    var failure =
        assertThrows(
            KeycloakProtectedAccounts.RemoteFailure.class, () -> transport.create(prepared));
    assertEquals(403, failure.status());
    assertNull(failure.getCause());
    assertFalse(failure.toString().contains("synthetic-password-or-bearer"));
    server.verify();
  }

  @Test
  void anotherBrokerActorCannotUseTheCreatorTransport() {
    var foreign = mock(TaskActorTokenBroker.VerifiedProvisioningToken.class);
    when(foreign.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);
    when(broker.issueCheckedProvisioning(TaskPurpose.ACCOUNT_PROVISIONING)).thenReturn(foreign);
    assertThrows(IllegalArgumentException.class, () -> transport.create(prepared));
    server.verify();
  }

  @Test
  void unpinnedOrCredentialBearingReceiverUrlsAreRefused() {
    for (String invalid :
        new String[] {
          "https://user:password@auth.example.test",
          "https://auth.example.test?query=1",
          "https://auth.example.test#fragment",
          "file:///tmp/native"
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new KeycloakProtectedAccounts(http, actors, broker, codec, invalid, "oriso"));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new KeycloakProtectedAccounts(
                http, actors, broker, codec, "https://auth.example.test", "other-realm"));
  }

  private String url() {
    return "https://auth.example.test/realms/oriso/oriso-protected-invitations/v1/approvals/"
        + APPROVAL
        + "/attempts/"
        + ATTEMPT;
  }

  private void refusesTuple(Runnable mutate, Runnable restore) {
    try {
      mutate.run();
      assertThrows(IllegalArgumentException.class, () -> transport.ownedRead(claim, created));
      verifyNoInteractions(actors, broker, codec);
    } finally {
      restore.run();
    }
  }
}
