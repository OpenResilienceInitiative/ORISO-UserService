package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.LocalOutcome;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.*;
import de.caritas.cob.userservice.api.config.auth.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

/**
 * Real RestTemplate request boundary; opaque codec handles are explicit doubles, not native
 * evidence.
 */
class ProtectedAccountRecoveryTest {
  private final RestTemplate http = new RestTemplate();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
  private final TaskActorConfiguration actors = mock(TaskActorConfiguration.class);
  private final TaskActorTokenBroker broker = mock(TaskActorTokenBroker.class);
  private final ProtectedAccountCodec codec = mock(ProtectedAccountCodec.class);
  private final PreparedRecovery prepared = mock(PreparedRecovery.class);
  private final VerifiedRecovery recovered = mock(VerifiedRecovery.class);
  private final VerifiedCleanup cleaned = mock(VerifiedCleanup.class);
  private final UUID approval = UUID.fromString("7f56c348-9785-4c78-b905-3d008dd42482");
  private final UUID attempt = UUID.fromString("39fe99aa-fd8e-44c8-9b48-71ea6cc09d38");
  private final LocalOutcome local =
      new LocalOutcome(1, UUID.fromString("d82d3425-977c-4cba-ae9d-4f02de29a5ab"), "d".repeat(64));
  private final byte[] body =
      "{\"synthetic\":\"exact-memory-request\"}".getBytes(StandardCharsets.UTF_8);
  private final byte[] response =
      "{\"synthetic\":\"exact-response\"}".getBytes(StandardCharsets.UTF_8);
  private ProtectedAccountRecovery transport;

  @BeforeEach
  void bindings() {
    lenient().when(codec.realmName()).thenReturn("oriso");
    lenient()
        .when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE))
        .thenReturn(
            new TaskActorCredentials(
                TaskActor.ACCOUNT_ADMIN,
                "backend-account-admin",
                "synthetic-only",
                "091b8cef-09a8-4e5b-a244-6be3db4b7bee"));
    var token = mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
    lenient().when(token.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);
    lenient().when(token.purpose()).thenReturn(TaskPurpose.ACCOUNT_MAINTENANCE);
    lenient().when(token.clientId()).thenReturn("backend-account-admin");
    lenient().when(token.subject()).thenReturn("091b8cef-09a8-4e5b-a244-6be3db4b7bee");
    lenient().when(token.accessToken()).thenReturn("public-synthetic-admin");
    lenient()
        .when(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE))
        .thenReturn(token);
    lenient().when(prepared.approvalId()).thenReturn(approval);
    lenient().when(prepared.attemptId()).thenReturn(attempt);
    lenient().when(recovered.approvalId()).thenReturn(approval);
    lenient().when(recovered.attemptId()).thenReturn(attempt);
    lenient().when(codec.recoverBody(prepared)).thenReturn(body);
    lenient().when(codec.compensateBody(recovered, local)).thenReturn(body);
    lenient()
        .when(codec.recoverProof(eq(prepared), any()))
        .thenReturn("public-synthetic-recovery-proof");
    lenient()
        .when(codec.compensateProof(eq(recovered), eq(local), any()))
        .thenReturn("public-synthetic-cleanup-proof");
    lenient().when(codec.verifyRecovery(eq(prepared), aryEq(response))).thenReturn(recovered);
    lenient()
        .when(codec.verifyCompensated(eq(recovered), eq(local), aryEq(response)))
        .thenReturn(cleaned);
    transport =
        new ProtectedAccountRecovery(
            http, actors, broker, codec, "https://auth.example.test", "oriso");
    clearInvocations(actors, broker, codec);
  }

  @Test
  void recoverUsesOnlyTheNativeFixedRouteWithFreshAdminAndOriginalBodyProof() {
    expect("recover", "public-synthetic-recovery-proof");
    assertSame(recovered, assertDoesNotThrow(() -> transport.recover(prepared)));
    verify(codec).verifyRecovery(eq(prepared), aryEq(response));
    verify(broker).issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
    verify(broker, never()).issue(TaskPurpose.ACCOUNT_MAINTENANCE);
    server.verify();
  }

  @Test
  void compensateRequiresTheVerifiedCurrentRecoveryAndExactLocalCleanupBeforeReturningData() {
    expect("compensate", "public-synthetic-cleanup-proof");
    assertSame(cleaned, assertDoesNotThrow(() -> transport.compensate(recovered, local)));
    verify(codec).verifyCompensated(eq(recovered), eq(local), aryEq(response));
    server.verify();
  }

  @Test
  void noNativeRequestOrTokenIssuanceMayRunWithinAmbientLocalSql() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThrows(IllegalStateException.class, () -> transport.recover(prepared));
      assertThrows(IllegalStateException.class, () -> transport.compensate(recovered, local));
      verifyNoInteractions(actors, broker, codec);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  @Test
  void anotherTaskActorCannotSelectTheCleanupTransport() {
    when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE))
        .thenReturn(
            new TaskActorCredentials(
                TaskActor.MAIL, "backend-mail", "synthetic", UUID.randomUUID().toString()));
    assertThrows(IllegalArgumentException.class, () -> transport.recover(prepared));
    verifyNoInteractions(broker);
    when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE))
        .thenReturn(
            new TaskActorCredentials(
                TaskActor.ACCOUNT_ADMIN,
                "foreign-client",
                "synthetic",
                UUID.randomUUID().toString()));
    assertThrows(IllegalArgumentException.class, () -> transport.recover(prepared));
  }

  @Test
  void unconfirmedOrMalformedSuccessCannotProduceRecoveryAuthority() {
    server
        .expect(requestTo(url("recover")))
        .andRespond(withStatus(HttpStatus.ACCEPTED).body(response));
    var failure =
        assertThrows(
            KeycloakProtectedAccounts.RemoteFailure.class, () -> transport.recover(prepared));
    assertEquals(202, failure.status());
    verify(codec, never()).verifyRecovery(any(), any());
    server.verify();
  }

  @Test
  void a200WithRejectedNativeReceiptRemainsUnknownWithNoRawResponseOrCause() {
    doThrow(new IllegalArgumentException("synthetic-private-response"))
        .when(codec)
        .verifyRecovery(eq(prepared), aryEq(response));
    expect("recover", "public-synthetic-recovery-proof");
    var failure =
        assertThrows(
            KeycloakProtectedAccounts.RemoteFailure.class, () -> transport.recover(prepared));
    assertEquals(0, failure.status());
    assertNull(failure.getCause());
    assertFalse(failure.toString().contains("synthetic-private-response"));
    server.verify();
  }

  @Test
  void providerFailuresExposeOnlyObservedStatusAndNeverReceiverBody() {
    server
        .expect(requestTo(url("compensate")))
        .andRespond(withStatus(HttpStatus.CONFLICT).body("synthetic-private-password"));
    var failure =
        assertThrows(
            KeycloakProtectedAccounts.RemoteFailure.class,
            () -> transport.compensate(recovered, local));
    assertEquals(409, failure.status());
    assertNull(failure.getCause());
    assertFalse(failure.toString().contains("synthetic-private-password"));
    server.verify();
  }

  @Test
  void configuredRealmAndServerCannotRedirectAPrivateRecoveryHandle() {
    for (String bad :
        new String[] {
          "https://user:password@auth.example.test",
          "https://auth.example.test/?target=foreign",
          "file:/tmp/receiver",
          "https://auth.example.test/#fragment"
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new ProtectedAccountRecovery(http, actors, broker, codec, bad, "oriso"));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProtectedAccountRecovery(
                http, actors, broker, codec, "https://auth.example.test", "foreign"));
    verifyNoInteractions(broker, actors);
  }

  @Test
  void terminalReconciliationUsesFreshSetupStatusOnlyAndNeverTheCommitRoute() {
    var created = mock(VerifiedCreated.class);
    var committed = mock(VerifiedCommitted.class);
    when(created.approvalId()).thenReturn(approval);
    when(actors.require(TaskPurpose.INVITE_RESERVATIONS))
        .thenReturn(
            new TaskActorCredentials(
                TaskActor.SETUP,
                "backend-setup",
                "synthetic",
                "0a14b4d5-c41e-44aa-acd5-4c77126f42bf"));
    when(broker.issue(TaskPurpose.INVITE_RESERVATIONS))
        .thenReturn(
            new TaskActorTokenBroker.TaskToken(TaskActor.SETUP, "public-synthetic-setup", 60));
    when(codec.consumedStatusBody(created)).thenReturn(body);
    when(codec.consumedStatusProof(eq(created), any())).thenReturn("public-synthetic-status-proof");
    when(codec.verifyConsumedStatus(eq(created), eq(local), aryEq(response))).thenReturn(committed);
    server
        .expect(
            requestTo(
                "https://auth.example.test/realms/oriso/oriso-protected-invitations/v1/approvals/"
                    + approval
                    + "/status"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer public-synthetic-setup"))
        .andExpect(header("X-ORISO-Task-Context", "public-synthetic-status-proof"))
        .andExpect(content().bytes(body))
        .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    assertSame(committed, assertDoesNotThrow(() -> transport.consumedStatus(created, local)));
    verify(broker).issue(TaskPurpose.INVITE_RESERVATIONS);
    verify(broker, never()).issue(TaskPurpose.ACCOUNT_PROVISIONING);
    verify(broker, never()).issue(TaskPurpose.ACCOUNT_MAINTENANCE);
    server.verify();
    clearInvocations(actors, broker, codec);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThrows(IllegalStateException.class, () -> transport.consumedStatus(created, local));
      verifyNoInteractions(actors, broker, codec);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  private void expect(String suffix, String proof) {
    server
        .expect(requestTo(url(suffix)))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer public-synthetic-admin"))
        .andExpect(header("X-ORISO-Task-Context", proof))
        .andExpect(content().bytes(body))
        .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
  }

  private String url(String suffix) {
    return "https://auth.example.test/realms/oriso/oriso-protected-invitations/v1/approvals/"
        + approval
        + "/attempts/"
        + attempt
        + "/"
        + suffix;
  }
}
