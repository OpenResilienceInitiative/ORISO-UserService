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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"DEBUG", "TRACE"})
  void trackedCreationKeepsExactWireAndCheckedProofWithoutRenderingOpaqueBytes(String level)
      throws Exception {
    credentials();
    var checked = mock(TaskActorTokenBroker.VerifiedProvisioningToken.class);
    when(checked.accessToken()).thenReturn("private-tracked-bearer-canary");
    when(broker.issueCheckedProvisioningPrivate(TaskPurpose.ACCOUNT_PROVISIONING))
        .thenReturn(checked);
    var identity = codec.identity(scope, account);
    var request = new CreateRequest(identity, execution, account);
    byte[] exact = codec.canonical(request).getBytes(StandardCharsets.UTF_8);
    var receipt =
        new AttemptResult(id, "account", "OPEN", execution, proof(identity, "account"), null, null);
    server
        .expect(requestTo(url()))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(content().bytes(exact))
        .andExpect(header("Authorization", "Bearer private-tracked-bearer-canary"))
        .andExpect(
            actual ->
                assertDoesNotThrow(
                    () -> {
                      String[] parts =
                          actual.getHeaders().getFirst("X-ORISO-Task-Context").split("\\.");
                      assertEquals(2, parts.length);
                      Mac mac = Mac.getInstance("HmacSHA256");
                      mac.init(new SecretKeySpec(key, "HmacSHA256"));
                      assertArrayEquals(
                          mac.doFinal(("proof\n" + parts[0]).getBytes(StandardCharsets.UTF_8)),
                          Base64.getUrlDecoder().decode(parts[1]));
                      var claims =
                          new com.fasterxml.jackson.databind.ObjectMapper()
                              .readTree(Base64.getUrlDecoder().decode(parts[0]));
                      assertEquals("account.create", claims.path("operation").asText());
                      assertEquals("PUT", claims.path("method").asText());
                      assertEquals("ACCOUNT_PROVISIONING", claims.path("purpose").asText());
                      assertEquals("ACCOUNT_CREATE", claims.path("actor").asText());
                      assertEquals(
                          "realm/realm-synthetic/account-attempt/" + id,
                          claims.path("target").asText());
                      assertEquals(
                          codec.digest("payload", request), claims.path("payloadDigest").asText());
                      assertEquals(owner.clientId(), claims.path("taskClient").asText());
                      assertEquals(owner.serviceSubject(), claims.path("taskSubject").asText());
                      assertEquals(60, claims.path("exp").asLong() - claims.path("iat").asLong());
                    }))
        .andRespond(withSuccess(codec.canonical(receipt), MediaType.APPLICATION_JSON));
    var root =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var old =
        new java.util.LinkedHashMap<ch.qos.logback.classic.Logger, ch.qos.logback.classic.Level>();
    var context =
        (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
    context.getLoggerList().stream()
        .filter(
            l ->
                l.getName().startsWith("org.springframework.web")
                    || l.getName().startsWith("org.springframework.http"))
        .forEach(
            l -> {
              old.put(l, l.getLevel());
              l.setLevel(ch.qos.logback.classic.Level.valueOf(level));
            });
    var events =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    events.start();
    root.addAppender(events);
    try {
      assertEquals(receipt, transport.createTracked(id, request));
      String messages =
          events.list.stream()
              .map(
                  e ->
                      e.getFormattedMessage()
                          + (e.getThrowableProxy() == null
                              ? ""
                              : ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(
                                  e.getThrowableProxy())))
              .collect(java.util.stream.Collectors.joining("\n"));
      for (String secret :
          java.util.List.of(
              account.password(),
              account.username(),
              "private-tracked-bearer-canary",
              owner.clientSecret())) {
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        assertFalse(messages.contains(secret));
        assertFalse(messages.contains(Base64.getEncoder().encodeToString(raw)));
        assertFalse(messages.contains(java.util.Arrays.toString(raw)));
        assertFalse(
            messages.contains(
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(secret)));
      }
      assertFalse(messages.contains(java.util.Arrays.toString(exact)));
      assertFalse(messages.contains(Base64.getEncoder().encodeToString(exact)));
    } finally {
      root.detachAppender(events);
      old.forEach(ch.qos.logback.classic.Logger::setLevel);
      events.stop();
    }
    verify(broker).issueCheckedProvisioningPrivate(TaskPurpose.ACCOUNT_PROVISIONING);
    verify(broker, never()).issueCheckedProvisioning(TaskPurpose.ACCOUNT_PROVISIONING);
    server.verify();
  }

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

  @Test
  void forgedSuccessfulRecoveryIsRejectedAtTheActualWireBoundary() throws Exception {
    credentials();
    var identity = codec.identity(scope, account);
    var request = new RecoverRequest(identity, 0, UUID.randomUUID());
    var result =
        new AttemptResult(
            id,
            "account",
            "RECOVERY_CLAIMED",
            new ExecutionRef(request.recoveryId(), 1),
            proof(identity, "account"),
            "invalid",
            null);
    server
        .expect(requestTo(url() + "/recover"))
        .andRespond(withSuccess(codec.canonical(result), MediaType.APPLICATION_JSON));
    assertThrows(IllegalArgumentException.class, () -> transport.recover(id, request));
    server.verify();
  }

  @Test
  void forgedSuccessfulTerminalIsRejectedAtTheActualWireBoundary() throws Exception {
    credentials();
    var identity = codec.identity(scope, account);
    var request =
        new CommitRequest(
            identity,
            execution,
            "account",
            proof(identity, "account"),
            new LocalOutcome(1, UUID.randomUUID(), "a".repeat(64)));
    var result =
        new AttemptResult(
            id, "account", "COMMITTED", execution, request.creationProof(), null, "invalid");
    server
        .expect(requestTo(url() + "/commit"))
        .andRespond(withSuccess(codec.canonical(result), MediaType.APPLICATION_JSON));
    assertThrows(IllegalArgumentException.class, () -> transport.commit(id, request));
    server.verify();
  }

  @Test
  void forgedWireRecoveryCannotPersistCleanupAuthorityInTheActualJournal() throws Exception {
    credentials();
    var fixture = journal();
    var identity = codec.identity(scope, account);
    var original = fixture.journal.begin(id, identity);
    String creation = proof(identity, "account");
    fixture.journal.created(
        id,
        identity,
        original,
        new AttemptResult(id, "account", "OPEN", original, creation, null, null));
    fixture.journal.requestRecovery(id, original);
    var pending = fixture.journal.withPending(id, value -> value);
    var request = codec.read(pending.body(), RecoverRequest.class);
    var result =
        new AttemptResult(
            id,
            "account",
            "RECOVERY_CLAIMED",
            new ExecutionRef(request.recoveryId(), original.generation() + 1),
            creation,
            "invalid",
            null);
    server
        .expect(requestTo(url() + "/recover"))
        .andRespond(withSuccess(codec.canonical(result), MediaType.APPLICATION_JSON));
    assertRejectedWithoutJournalProgress(fixture);
    server.verify();
  }

  @Test
  void forgedWireTerminalCannotReleaseTheActualJournalLease() throws Exception {
    credentials();
    var fixture = journal();
    var identity = codec.identity(scope, account);
    var original = fixture.journal.begin(id, identity);
    String creation = proof(identity, "account");
    fixture.journal.created(
        id,
        identity,
        original,
        new AttemptResult(id, "account", "OPEN", original, creation, null, null));
    fixture.journal.inLocalSaga(
        id,
        original,
        value -> {
          fixture.journal.prepareCommitInSaga(id, original);
          return null;
        });
    var result = new AttemptResult(id, "account", "COMMITTED", original, creation, null, "invalid");
    server
        .expect(requestTo(url() + "/commit"))
        .andRespond(withSuccess(codec.canonical(result), MediaType.APPLICATION_JSON));
    assertRejectedWithoutJournalProgress(fixture);
    server.verify();
  }

  private void assertRejectedWithoutJournalProgress(JournalFixture fixture) {
    var before =
        fixture.jdbc.queryForMap(
            "select * from identity_lifecycle_attempt where id=?", id.toString());
    var outbox =
        fixture.jdbc.queryForList(
            "select * from identity_lifecycle_outbox where attempt_id=?", id.toString());
    var effects = mock(AccountLifecycleEffects.class);
    var dispatcher = new AccountLifecycleDispatcher(transport, fixture.journal, effects, codec);
    assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(id));
    assertEquals(
        before,
        fixture.jdbc.queryForMap(
            "select * from identity_lifecycle_attempt where id=?", id.toString()));
    assertEquals(
        outbox,
        fixture.jdbc.queryForList(
            "select * from identity_lifecycle_outbox where attempt_id=?", id.toString()));
    verifyNoInteractions(effects);
  }

  private record JournalFixture(
      org.springframework.jdbc.core.JdbcTemplate jdbc, JdbcAccountLifecycleJournal journal) {}

  private JournalFixture journal() throws Exception {
    var source = new org.h2.jdbcx.JdbcDataSource();
    source.setURL("jdbc:h2:mem:receipt-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
    for (String name : List.of("migrate.sql", "retry-fairness.sql"))
      for (String sql :
          java.nio.file.Files.readString(
                  java.nio.file.Path.of(
                      "src/main/resources/db/changelog/changeset/20261021_identity_lifecycle_367/"
                          + name))
              .split(";")) if (!sql.isBlank()) jdbc.execute(sql);
    return new JournalFixture(
        jdbc,
        new JdbcAccountLifecycleJournal(
            jdbc,
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(source),
            codec,
            java.time.Clock.systemUTC(),
            300));
  }

  @Test
  void validOriginalRecoveryAndCompensationReceiptsKeepTheirDistinctStatesAndDigests()
      throws Exception {
    credentials();
    var identity = codec.identity(scope, account);
    var recover = new RecoverRequest(identity, 0, UUID.randomUUID());
    var fence = new ExecutionRef(recover.recoveryId(), 1);
    String creation = proof(identity, "account");
    var recovered =
        new AttemptResult(id, "account", "RECOVERY_CLAIMED", fence, creation, "placeholder", null);
    String recovery =
        signedReceipt(
            recovered,
            identity,
            "RECOVERY_CLAIMED",
            "recover",
            codec.digest("payload", recover),
            "recovery-receipt");
    recovered =
        new AttemptResult(id, "account", "RECOVERY_CLAIMED", fence, creation, recovery, null);
    var compensate =
        new CompensateRequest(
            identity,
            fence,
            "account",
            creation,
            new LocalOutcome(2, UUID.randomUUID(), "a".repeat(64)));
    var compensated =
        new AttemptResult(id, "account", "COMPENSATED", fence, creation, recovery, "placeholder");
    String terminal =
        signedReceipt(
            compensated,
            identity,
            "COMPENSATED",
            "compensate",
            codec.digest("payload", compensate),
            "terminal-receipt");
    compensated =
        new AttemptResult(id, "account", "COMPENSATED", fence, creation, recovery, terminal);
    respond("recover", recovered);
    respond("compensate", compensated);
    respond("recover", compensated);
    assertEquals(recovered, transport.recover(id, recover));
    assertEquals(compensated, transport.compensate(id, compensate));
    assertEquals(compensated, transport.recover(id, recover));
    server.verify();
  }

  @Test
  void absentRecoveryAndCompensationReplayKeepTheOriginalAbandonReceipt() throws Exception {
    credentials();
    var identity = codec.identity(scope, account);
    var recover = new RecoverRequest(identity, 0, UUID.randomUUID());
    var result =
        new AttemptResult(
            id,
            null,
            "ABANDONED",
            new ExecutionRef(recover.recoveryId(), 1),
            null,
            null,
            "placeholder");
    String digest = codec.digest("payload", recover);
    String recovery =
        signedReceipt(result, identity, "ABANDONED", "recover", digest, "recovery-receipt");
    String terminal =
        signedReceipt(result, identity, "ABANDONED", "abandon", digest, "terminal-receipt");
    result = new AttemptResult(id, null, "ABANDONED", result.execution(), null, recovery, terminal);
    var compensate =
        new CompensateRequest(
            identity,
            result.execution(),
            null,
            null,
            new LocalOutcome(2, UUID.randomUUID(), "a".repeat(64)));
    respond("recover", result);
    respond("compensate", result);
    respond("recover", result);
    assertEquals(result, transport.recover(id, recover));
    assertEquals(result, transport.compensate(id, compensate));
    assertEquals(result, transport.recover(id, new RecoverRequest(identity, 1, UUID.randomUUID())));
    server.verify();
  }

  @Test
  void alreadyCommittedRecoveryAuthenticatesHistoricalCommitWithoutTreatingItAsANewFence()
      throws Exception {
    credentials();
    var identity = codec.identity(scope, account);
    String creation = proof(identity, "account");
    var commit =
        new CommitRequest(
            identity,
            execution,
            "account",
            creation,
            new LocalOutcome(1, UUID.randomUUID(), "a".repeat(64)));
    var result =
        new AttemptResult(id, "account", "COMMITTED", execution, creation, null, "placeholder");
    result =
        new AttemptResult(
            id,
            "account",
            "COMMITTED",
            execution,
            creation,
            null,
            signedReceipt(
                result,
                identity,
                "COMMITTED",
                "commit",
                codec.digest("payload", commit),
                "terminal-receipt"));
    respond("commit", result);
    respond("recover", result);
    assertEquals(result, transport.commit(id, commit));
    assertEquals(result, transport.recover(id, new RecoverRequest(identity, 0, UUID.randomUUID())));
    server.verify();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "mac",
        "domain",
        "extra",
        "missing",
        "scopeType",
        "realmId",
        "attemptId",
        "accountId",
        "ownerClient",
        "ownerSubject",
        "scope",
        "creationFingerprint",
        "groupId",
        "roleFingerprint",
        "state",
        "generation",
        "executionId",
        "operation",
        "commandDigest",
        "noncanonical",
        "uppercaseMac"
      })
  void malformedOrForeignSignedRecoveryCannotCrossTheWireBoundary(String change) throws Exception {
    credentials();
    var identity = codec.identity(scope, account);
    var request = new RecoverRequest(identity, 0, UUID.randomUUID());
    var result =
        new AttemptResult(
            id,
            "account",
            "RECOVERY_CLAIMED",
            new ExecutionRef(request.recoveryId(), 1),
            proof(identity, "account"),
            "placeholder",
            null);
    var payload =
        receiptPayload(
            result, identity, "RECOVERY_CLAIMED", "recover", codec.digest("payload", request));
    switch (change) {
      case "extra" -> payload.put("extra", "foreign");
      case "missing" -> payload.remove("groupId");
      case "scopeType" -> payload.put("scope", scope);
      case "generation" -> payload.put("generation", 2);
      case "attemptId", "executionId" -> payload.put(change, UUID.randomUUID().toString());
      case "creationFingerprint", "roleFingerprint", "commandDigest" ->
          payload.put(change, "f".repeat(64));
      case "mac", "domain", "noncanonical", "uppercaseMac" -> {}
      default -> payload.put(change, "foreign");
    }
    String raw = codec.canonical(payload);
    if (change.equals("noncanonical")) raw = " " + raw;
    String signed =
        signReceipt(raw, change.equals("domain") ? "terminal-receipt" : "recovery-receipt");
    if (change.equals("mac"))
      signed = signed.substring(0, signed.indexOf('.') + 1) + "0".repeat(64);
    if (change.equals("uppercaseMac"))
      signed =
          signed.substring(0, signed.indexOf('.') + 1)
              + signed.substring(signed.indexOf('.') + 1).toUpperCase(java.util.Locale.ROOT);
    respond(
        "recover",
        new AttemptResult(
            id,
            "account",
            "RECOVERY_CLAIMED",
            result.execution(),
            result.creationProof(),
            signed,
            null));
    assertThrows(IllegalArgumentException.class, () -> transport.recover(id, request));
    server.verify();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"commit", "compensate"})
  void validMacCannotAuthenticateAnotherTerminalCommandDigest(String operation) throws Exception {
    credentials();
    var identity = codec.identity(scope, account);
    String creation = proof(identity, "account");
    String state = operation.equals("commit") ? "COMMITTED" : "COMPENSATED";
    var result = new AttemptResult(id, "account", state, execution, creation, null, "placeholder");
    result =
        new AttemptResult(
            id,
            "account",
            state,
            execution,
            creation,
            null,
            signedReceipt(result, identity, state, operation, "f".repeat(64), "terminal-receipt"));
    respond(operation, result);
    var local = new LocalOutcome(1, UUID.randomUUID(), "a".repeat(64));
    if (operation.equals("commit"))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              transport.commit(
                  id, new CommitRequest(identity, execution, "account", creation, local)));
    else
      assertThrows(
          IllegalArgumentException.class,
          () ->
              transport.compensate(
                  id, new CompensateRequest(identity, execution, "account", creation, local)));
    server.verify();
  }

  @Test
  void lostRecoveryReplyRetainsOriginalJournalBytesThenAuthenticatedFenceAloneEnablesCleanup()
      throws Exception {
    credentials();
    var fixture = journal();
    var identity = codec.identity(scope, account);
    var original = fixture.journal.begin(id, identity);
    String creation = proof(identity, "account");
    fixture.journal.created(
        id,
        identity,
        original,
        new AttemptResult(id, "account", "OPEN", original, creation, null, null));
    fixture.journal.requestRecovery(id, original);
    var pending = fixture.journal.withPending(id, value -> value);
    var recover = codec.read(pending.body(), RecoverRequest.class);
    var result =
        new AttemptResult(
            id,
            "account",
            "RECOVERY_CLAIMED",
            new ExecutionRef(recover.recoveryId(), 1),
            creation,
            "placeholder",
            null);
    result =
        new AttemptResult(
            id,
            "account",
            "RECOVERY_CLAIMED",
            result.execution(),
            creation,
            signedReceipt(
                result,
                identity,
                "RECOVERY_CLAIMED",
                "recover",
                codec.digest("payload", recover),
                "recovery-receipt"),
            null);
    server
        .expect(requestTo(url() + "/recover"))
        .andExpect(content().bytes(pending.body().getBytes(StandardCharsets.UTF_8)))
        .andRespond(
            request -> {
              throw new java.io.IOException("synthetic lost reply");
            });
    server
        .expect(requestTo(url() + "/recover"))
        .andExpect(content().bytes(pending.body().getBytes(StandardCharsets.UTF_8)))
        .andRespond(withSuccess(codec.canonical(result), MediaType.APPLICATION_JSON));
    var effects = mock(AccountLifecycleEffects.class);
    var dispatcher = new AccountLifecycleDispatcher(transport, fixture.journal, effects, codec);
    assertThrows(
        KeycloakAccountLifecycleCommands.RemoteFailure.class, () -> dispatcher.dispatch(id));
    assertEquals(pending, fixture.journal.withPending(id, value -> value));
    verifyNoInteractions(effects);
    new AccountLifecycleDispatcher(transport, fixture.journal, effects, codec).dispatch(id);
    var cleanup = fixture.journal.withPending(id, value -> value);
    assertEquals("CLEANUP", cleanup.operation());
    assertEquals(result.execution(), cleanup.attempt().execution());
    verifyNoInteractions(effects);
    dispatcher.dispatch(id);
    verify(effects).clean(cleanup.attempt());
    assertEquals("COMPENSATE", fixture.journal.withPending(id, value -> value.operation()));
    server.verify();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"COMMITTED", "COMPENSATED", "ABANDONED"})
  void authenticatedHistoricalTerminalReconcilesWithoutCleanupOrReleasingTheLease(String state)
      throws Exception {
    credentials();
    var fixture = journal();
    var identity = codec.identity(scope, account);
    var original = fixture.journal.begin(id, identity);
    boolean abandoned = state.equals("ABANDONED");
    String creation = abandoned ? null : proof(identity, "account");
    if (!abandoned)
      fixture.journal.created(
          id,
          identity,
          original,
          new AttemptResult(id, "account", "OPEN", original, creation, null, null));
    fixture.journal.requestRecovery(id, original);
    var pending = fixture.journal.withPending(id, value -> value);
    var currentRequest = codec.read(pending.body(), RecoverRequest.class);
    var nativeExecution = abandoned ? new ExecutionRef(UUID.randomUUID(), 1) : original;
    if (abandoned) {
      // Reconstitute an owned pending reconciliation at the already-observed native generation.
      currentRequest = new RecoverRequest(identity, 1, currentRequest.recoveryId());
      fixture.jdbc.update(
          "update identity_lifecycle_attempt set generation=1,execution_id=? where id=?",
          nativeExecution.executionId().toString(),
          id.toString());
      fixture.jdbc.update(
          "update identity_lifecycle_outbox set body_json=? where id=?",
          codec.canonical(currentRequest),
          pending.outboxId().toString());
    }
    String originalDigest =
        abandoned
            ? codec.digest(
                "payload", new RecoverRequest(identity, 0, nativeExecution.executionId()))
            : codec.digest(
                "payload",
                state.equals("COMMITTED")
                    ? new CommitRequest(
                        identity,
                        original,
                        "account",
                        creation,
                        new LocalOutcome(1, UUID.randomUUID(), "a".repeat(64)))
                    : new CompensateRequest(
                        identity,
                        original,
                        "account",
                        creation,
                        new LocalOutcome(1, UUID.randomUUID(), "a".repeat(64))));
    var result =
        new AttemptResult(
            id,
            abandoned ? null : "account",
            state,
            nativeExecution,
            creation,
            null,
            "placeholder");
    String recovery =
        abandoned
            ? signedReceipt(result, identity, state, "recover", originalDigest, "recovery-receipt")
            : null;
    String terminal =
        signedReceipt(
            result,
            identity,
            state,
            abandoned ? "abandon" : state.equals("COMMITTED") ? "commit" : "compensate",
            originalDigest,
            "terminal-receipt");
    result =
        new AttemptResult(
            id, result.accountId(), state, nativeExecution, creation, recovery, terminal);
    var before =
        fixture.jdbc.queryForMap(
            "select * from identity_lifecycle_attempt where id=?", id.toString());
    respond("recover", result);
    var effects = mock(AccountLifecycleEffects.class);
    new AccountLifecycleDispatcher(transport, fixture.journal, effects, codec).dispatch(id);
    var after =
        fixture.jdbc.queryForMap(
            "select * from identity_lifecycle_attempt where id=?", id.toString());
    assertEquals("LOCAL_RECONCILIATION_REQUIRED", after.get("STATUS"));
    for (String field :
        List.of(
            "LEASE_UNTIL",
            "ACCOUNT_ID",
            "CREATION_PROOF",
            "GENERATION",
            "EXECUTION_ID",
            "RECOVERY_RECEIPT",
            "TERMINAL_RECEIPT")) assertEquals(before.get(field), after.get(field), field);
    assertEquals(
        0,
        fixture.jdbc.queryForObject(
            "select count(*) from identity_lifecycle_outbox where operation in ('CLEANUP','COMPENSATE')",
            Integer.class));
    verifyNoInteractions(effects);
    server.verify();
  }

  @Test
  void authenticatedHistoricalTerminalForAnotherKnownAccountKeepsOriginalCustody()
      throws Exception {
    credentials();
    var fixture = journal();
    var identity = codec.identity(scope, account);
    var original = fixture.journal.begin(id, identity);
    String creation = proof(identity, "account");
    fixture.journal.created(
        id,
        identity,
        original,
        new AttemptResult(id, "account", "OPEN", original, creation, null, null));
    fixture.journal.requestRecovery(id, original);
    var pending = fixture.journal.withPending(id, value -> value);
    var before =
        fixture.jdbc.queryForMap(
            "select * from identity_lifecycle_attempt where id=?", id.toString());
    var result =
        new AttemptResult(
            id,
            "foreign-account",
            "COMMITTED",
            original,
            proof(identity, "foreign-account"),
            null,
            "placeholder");
    result =
        new AttemptResult(
            id,
            result.accountId(),
            result.state(),
            original,
            result.creationProof(),
            null,
            signedReceipt(
                result, identity, "COMMITTED", "commit", "a".repeat(64), "terminal-receipt"));
    respond("recover", result);
    var effects = mock(AccountLifecycleEffects.class);
    assertThrows(
        IllegalStateException.class,
        () ->
            new AccountLifecycleDispatcher(transport, fixture.journal, effects, codec)
                .dispatch(id));
    assertEquals(
        before,
        fixture.jdbc.queryForMap(
            "select * from identity_lifecycle_attempt where id=?", id.toString()));
    assertEquals(pending, fixture.journal.withPending(id, value -> value));
    verifyNoInteractions(effects);
    server.verify();
  }

  @Test
  void authenticatedTerminalAtAnotherGenerationCannotBeReinterpretedAsCurrentRecovery()
      throws Exception {
    credentials();
    var identity = codec.identity(scope, account);
    var request = new RecoverRequest(identity, 0, UUID.randomUUID());
    var result =
        new AttemptResult(
            id,
            "account",
            "COMMITTED",
            new ExecutionRef(UUID.randomUUID(), 2),
            proof(identity, "account"),
            null,
            "placeholder");
    result =
        new AttemptResult(
            id,
            result.accountId(),
            result.state(),
            result.execution(),
            result.creationProof(),
            null,
            signedReceipt(
                result, identity, "COMMITTED", "commit", "a".repeat(64), "terminal-receipt"));
    respond("recover", result);
    assertThrows(IllegalArgumentException.class, () -> transport.recover(id, request));
    server.verify();
  }

  private void respond(String operation, AttemptResult result) {
    server
        .expect(requestTo(url() + "/" + operation))
        .andRespond(withSuccess(codec.canonical(result), MediaType.APPLICATION_JSON));
  }

  private java.util.TreeMap<String, Object> receiptPayload(
      AttemptResult result,
      CreationIdentity identity,
      String state,
      String operation,
      String digest) {
    var value = new java.util.TreeMap<String, Object>();
    value.put("realmId", "realm-synthetic");
    value.put("attemptId", id.toString());
    value.put("accountId", result.accountId());
    value.put("ownerClient", owner.clientId());
    value.put("ownerSubject", owner.serviceSubject());
    value.put("scope", codec.canonical(identity.scope()));
    value.put("creationFingerprint", identity.creationFingerprint());
    value.put("groupId", "group-synthetic");
    value.put("roleFingerprint", codec.digest("initial-roles", List.of("user")));
    value.put("state", state);
    value.put("generation", result.execution().generation());
    value.put("executionId", result.execution().executionId().toString());
    value.put("operation", operation);
    value.put("commandDigest", digest);
    return value;
  }

  private String signedReceipt(
      AttemptResult result,
      CreationIdentity identity,
      String state,
      String operation,
      String digest,
      String domain)
      throws Exception {
    return signReceipt(
        codec.canonical(receiptPayload(result, identity, state, operation, digest)), domain);
  }

  private String signReceipt(String payload, String domain) throws Exception {
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return encoded
        + "."
        + java.util.HexFormat.of()
            .formatHex(mac.doFinal((domain + "\n" + encoded).getBytes(StandardCharsets.UTF_8)));
  }

  private void credentials() {
    when(actors.require(TaskPurpose.ACCOUNT_PROVISIONING)).thenReturn(owner);
    when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE))
        .thenReturn(
            new TaskActorCredentials(
                TaskActor.ACCOUNT_ADMIN,
                "backend-account-admin",
                "synthetic-secret",
                "22222222-2222-4222-8222-222222222222"));
    var maintenance = mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
    when(maintenance.accessToken()).thenReturn("synthetic-maintenance");
    when(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(maintenance);
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
