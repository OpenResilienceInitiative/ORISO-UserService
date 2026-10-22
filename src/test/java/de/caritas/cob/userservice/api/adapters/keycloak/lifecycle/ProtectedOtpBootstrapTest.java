package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.AccountCreation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.KeycloakProtectedAccounts.PendingOwnedAccount;
import de.caritas.cob.userservice.api.config.auth.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

/** Real owning SQL/opaque claim and HTTP handoff; responses use public synthetic MAC material. */
class ProtectedOtpBootstrapTest {
  @Test
  void actualOwnedPendingAccountPreparesOutsideSqlThenPublishesSetupOnlyWithOwningCommit()
      throws Exception {
    try (var f = new Fixture()) {
      f.expectPrepare();
      var prepared = assertDoesNotThrow(() -> f.adapter().prepare(f.claim, f.pending));
      assertFalse(prepared.toString().contains("SyntheticSetupSecret1234567890"));
      assertThrows(RuntimeException.class, () -> f.adapter().currentSetup(f.claim));
      var credential =
          new TransactionTemplate(f.manager)
              .execute(tx -> f.adapter().stagePrepared(f.claim, prepared));
      assertNotNull(credential);
      assertEquals("SyntheticSetupSecret1234567890", credential.secret());
      assertFalse(credential.setup());
      assertEquals("SyntheticSetupSecret1234567890", f.adapter().currentSetup(f.claim).secret());
      assertEquals(
          1, f.jdbc.queryForObject("SELECT COUNT(*) FROM protected_otp_bootstrap", Integer.class));
      String saved = f.jdbc.queryForList("SELECT * FROM protected_otp_bootstrap").toString();
      assertFalse(saved.contains("SyntheticSetupSecret1234567890"));
      assertFalse(saved.contains(f.pending.username()));
      assertFalse(saved.contains("Bearer"));
      assertThrows(RuntimeException.class, () -> f.adapter().stagePrepared(f.claim, prepared));
      f.server.verify();
      f.server.reset();
      f.request.setRequestURI("/service/tenant/register/code");
      f.request.removeHeader("Idempotency-Key");
      f.request.addHeader("Idempotency-Key", "48706e6f-c812-47fc-98fb-20b5ece608a2");
      f.expectApp();
      var attempt = assertDoesNotThrow(() -> f.adapter().setupApp(f.claim, "123456"));
      assertEquals(ProtectedOtpBootstrap.AttemptState.COMPLETE, attempt.state());
      var completion = attempt.completion();
      assertEquals(f.claim.claimId(), completion.claimId());
      assertEquals(f.claim.attemptId(), completion.attemptId());
      assertEquals(f.claim.executionId(), completion.executionId());
      assertEquals(f.claim.executionId(), completion.bootstrapIncarnation());
      assertEquals(f.pending.created().accountId(), completion.accountId());
      assertEquals(completion.receipt(), f.adapter().completed(f.claim).receipt());
      assertFalse(completion.toString().contains("123456"));
      assertFalse(completion.receipt().contains("SyntheticSetupSecret1234567890"));
      assertEquals(
          1,
          f.jdbc.queryForObject(
              "SELECT COUNT(*) FROM protected_otp_bootstrap_operation WHERE state='ACKNOWLEDGED'",
              Integer.class));
      String intents =
          f.jdbc.queryForList("SELECT * FROM protected_otp_bootstrap_operation").toString();
      assertFalse(intents.contains("SyntheticSetupSecret1234567890"));
      assertFalse(intents.contains("123456"));
      f.server.verify();
    }
  }

  @Test
  void anotherDataSourceCannotOwnTheBootstrapJournal() throws Exception {
    try (var f = new Fixture()) {
      var foreign = new JdbcDataSource();
      foreign.setURL("jdbc:h2:mem:" + UUID.randomUUID());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new ProtectedOtpBootstrap(
                  new JdbcTemplate(foreign),
                  f.manager,
                  f.json,
                  f.issuer.actors(),
                  f.issuer.broker(),
                  f.http,
                  f.request,
                  Clock.systemUTC(),
                  f.issuer.base(),
                  "synthetic",
                  "native-synthetic-realm"));
    }
  }

  @Test
  void anUnrelatedOwningTransactionCannotCommitTheSetupJournal() throws Exception {
    try (var f = new Fixture()) {
      f.expectPrepare();
      var prepared = assertDoesNotThrow(() -> f.adapter().prepare(f.claim, f.pending));
      var foreign = new JdbcDataSource();
      foreign.setURL("jdbc:h2:mem:" + UUID.randomUUID());
      assertThrows(
          RuntimeException.class,
          () ->
              new TransactionTemplate(new DataSourceTransactionManager(foreign))
                  .execute(tx -> f.adapter().stagePrepared(f.claim, prepared)));
      assertEquals(
          "PREPARED",
          f.jdbc.queryForObject("SELECT state FROM protected_otp_bootstrap", String.class));
      assertThrows(RuntimeException.class, () -> f.adapter().currentSetup(f.claim));
      f.server.verify();
    }
  }

  @Test
  void theExactOwningTransactionCanReadCommittedSetupWithoutAnotherRemoteRequest()
      throws Exception {
    try (var f = new Fixture()) {
      f.expectPrepare();
      var prepared = assertDoesNotThrow(() -> f.adapter().prepare(f.claim, f.pending));
      new TransactionTemplate(f.manager)
          .executeWithoutResult(tx -> f.adapter().stagePrepared(f.claim, prepared));
      var setup =
          assertDoesNotThrow(
              () ->
                  new TransactionTemplate(f.manager)
                      .execute(tx -> f.adapter().currentSetupWithinOwner(f.claim)));
      assertNotNull(setup);
      assertEquals("SyntheticSetupSecret1234567890", setup.secret());
      f.server.verify();
    }
  }

  @Test
  void currentRecipientCanReadSetupAfterMachineExpiryWhileOriginalInvitationRemainsLive()
      throws Exception {
    try (var f = new Fixture()) {
      f.expectPrepare();
      var prepared = assertDoesNotThrow(() -> f.adapter().prepare(f.claim, f.pending));
      new TransactionTemplate(f.manager)
          .executeWithoutResult(tx -> f.adapter().stagePrepared(f.claim, prepared));
      Clock afterMachine = Clock.offset(f.clock, java.time.Duration.ofSeconds(400));
      var setup = assertDoesNotThrow(() -> f.adapter(afterMachine).currentSetup(f.claim));
      assertEquals("SyntheticSetupSecret1234567890", setup.secret());
      f.jdbc.update("UPDATE account_invite SET token_hash=? WHERE id=73", "d".repeat(64));
      assertThrows(RuntimeException.class, () -> f.adapter(afterMachine).currentSetup(f.claim));
      f.server.verify();
    }
  }

  private static final class Fixture implements AutoCloseable {
    private static final String ADMIN = "032d47ad-8156-47b8-a8ce-17ae1970e046";
    private final Clock clock = Clock.systemUTC();
    private final JsonNode vector;
    private final byte[] key;
    private final AccountLifecycleCodec json;
    private final SelfAppSignedIssuer issuer = new SelfAppSignedIssuer(ADMIN);
    private final JdbcTemplate jdbc;
    private final DataSourceTransactionManager manager;
    private final MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/service/tenant/register");
    private final RestTemplate http = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    private final AcceptanceClaim claim;
    private final PendingOwnedAccount pending;
    private ObjectNode prepareFacts;
    private String prepareReceipt;

    Fixture() throws Exception {
      request.addHeader("Idempotency-Key", "6edf02c1-7a04-4e19-99aa-fd5bc479ba15");
      var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      vector =
          mapper.readTree(
              Files.readString(Path.of("src/test/resources/scenario-c/protected-account-v1.json")));
      key = HexFormat.of().parseHex(vector.path("syntheticPublicKeyHex").asText());
      json = new AccountLifecycleCodec(key);
      var source = new JdbcDataSource();
      source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
      jdbc = new JdbcTemplate(source);
      manager = new DataSourceTransactionManager(source);
      jdbc.execute(
          "CREATE TABLE account_invite(id BIGINT PRIMARY KEY,target_role VARCHAR(64),tenant_id BIGINT,recipient_email VARCHAR(255),purpose VARCHAR(32),status VARCHAR(32),expires_at TIMESTAMP,token_hash VARCHAR(64),agency_id BIGINT,tenant_id_reservation_token VARCHAR(36))");
      for (String name :
          new String[] {
            "20261022_protected_invitation_367",
            "20261022_protected_invitation_acceptance_367",
            "20261022_protected_otp_bootstrap_367"
          }) {
        Path migration =
            Path.of("src/main/resources/db/changelog/changeset/" + name + "/migrate.sql");
        // The new journal migration is part of this vertical slice, never optional in acceptance.
        for (String sql : Files.readString(migration).split(";"))
          if (!sql.isBlank()) jdbc.execute(sql);
      }
      var approvals =
          new ProtectedInvitationCodec(key, clock, "synthetic", "native-synthetic-realm");
      var av = vector.path("approvalVector");
      var frozen =
          approvals.freeze(
              UUID.fromString(av.path("approvalId").asText()),
              json.canonical(av.path("request")),
              av.path("issuerRealm").asText(),
              av.path("issuerSubject").asText());
      jdbc.update(
          "INSERT INTO account_invite VALUES(73,'TENANT_ADMIN',7,'ada@example.test','INVITE','EMAIL_SENT',?,?,NULL,NULL)",
          Timestamp.from(Instant.parse("2030-01-02T03:04:05Z")),
          "c".repeat(64));
      var journal = new JdbcProtectedInvitationJournal(jdbc, manager, approvals, json, clock);
      journal.propose(73, frozen);
      journal.recordReady(
          frozen.approvalId(),
          approvals.verifyAuthorized(frozen, json.canonical(av.path("response"))));
      jdbc.update(
          "INSERT INTO protected_invitation_acceptance(invite_id,approval_id,claim_id,attempt_id,execution_id,journal_version,registration_digest,created_at,updated_at) VALUES(73,?,?,?,?,1,?,?,?)",
          frozen.approvalId().toString(),
          vector.path("claimId").asText(),
          vector.path("attemptId").asText(),
          vector.path("executionId").asText(),
          vector.path("registrationDigest").asText(),
          Timestamp.from(clock.instant()),
          Timestamp.from(clock.instant()));
      claim =
          new JdbcProtectedInvitationAcceptance(jdbc, manager, journal, approvals, json, clock)
              .claim(73, "c".repeat(64), vector.path("registration"));
      var codec =
          new ProtectedAccountCodec(
              key,
              clock,
              approvals,
              "synthetic",
              "native-synthetic-realm",
              vector.path("realmIssuer").asText());
      var prepared =
          codec.prepare(
              claim,
              vector.path("registration"),
              json.read(json.canonical(vector.path("account")), AccountCreation.class));
      var created =
          codec.verifyCreated(
              prepared,
              json.canonical(vector.path("createdResponse")).getBytes(StandardCharsets.UTF_8));
      var creators = mock(TaskActorConfiguration.class);
      var creator =
          new TaskActorCredentials(
              TaskActor.ACCOUNT_CREATE,
              "backend-account-create",
              "synthetic",
              av.path("request").path("scope").path("creatorSubject").asText());
      when(creators.require(TaskPurpose.ACCOUNT_PROVISIONING)).thenReturn(creator);
      var broker = mock(TaskActorTokenBroker.class);
      when(broker.issue(TaskPurpose.ACCOUNT_PROVISIONING))
          .thenReturn(
              new TaskActorTokenBroker.TaskToken(
                  TaskActor.ACCOUNT_CREATE, "synthetic-creator-bearer", 60));
      server
          .expect(
              requestTo(
                  issuer.base()
                      + "/realms/synthetic/oriso-protected-invitations/v1/approvals/"
                      + created.approvalId()
                      + "/attempts/"
                      + created.attemptId()
                      + "/owned-read"))
          .andExpect(method(HttpMethod.POST))
          .andRespond(
              withSuccess(
                  json.canonical(vector.path("ownedProjection")), MediaType.APPLICATION_JSON));
      pending =
          new KeycloakProtectedAccounts(http, creators, broker, codec, issuer.base(), "synthetic")
              .ownedRead(claim, created);
      server.verify();
      server.reset();
    }

    ProtectedOtpBootstrap adapter() {
      return adapter(clock);
    }

    ProtectedOtpBootstrap adapter(Clock actualClock) {
      return new ProtectedOtpBootstrap(
          jdbc,
          manager,
          json,
          issuer.actors(),
          issuer.broker(),
          http,
          request,
          actualClock,
          issuer.base(),
          "synthetic",
          "native-synthetic-realm");
    }

    void expectPrepare() {
      server
          .expect(
              requestTo(
                  org.springframework.web.util.UriComponentsBuilder.fromUriString(issuer.base())
                      .pathSegment(
                          "realms",
                          "synthetic",
                          "otp-config",
                          "fetch-otp-setup-info",
                          pending.username())
                      .build()
                      .encode()
                      .toUri()))
          .andExpect(method(HttpMethod.GET))
          .andRespond(
              req -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertNotNull(req.getHeaders().getFirst("Authorization"));
                assertNull(req.getHeaders().getFirst("X-ORISO-Human-Authorization"));
                JsonNode target =
                    json.read(req.getHeaders().getFirst("X-ORISO-Account-Target"), JsonNode.class);
                assertEquals(pending.created().accountId(), target.path("accountId").asText());
                assertEquals(
                    claim.claimId().toString(),
                    target.path("bootstrap").path("acceptanceClaim").path("claimId").asText());
                assertEquals(
                    1,
                    jdbc.queryForObject(
                        "SELECT COUNT(*) FROM protected_otp_bootstrap", Integer.class));
                long deadline = target.path("bootstrap").path("authorityValidUntil").asLong();
                ObjectNode receipt =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
                receipt.put("v", 1);
                receipt.put("realmId", "native-synthetic-realm");
                receipt.put("approvalId", claim.frozen().approvalId().toString());
                receipt.put("attemptId", claim.attemptId().toString());
                receipt.put("accountId", pending.created().accountId());
                receipt.put("claimId", claim.claimId().toString());
                receipt.put("executionId", claim.executionId().toString());
                receipt.put("bootstrapIncarnation", claim.executionId().toString());
                receipt.put("ownerClient", "backend-account-admin");
                receipt.put("ownerSubject", ADMIN);
                receipt.put("originDigest", json.digest("otp-bootstrap-origin/v1", target));
                receipt.put("authorityValidUntil", deadline);
                receipt.put("state", "PREPARED");
                String encoded =
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(json.canonical(receipt).getBytes(StandardCharsets.UTF_8));
                String sealed = nativeReceipt(key, "otp-bootstrap-prepare/v1", encoded);
                prepareFacts = receipt.deepCopy();
                prepareReceipt = sealed;
                String result =
                    json.canonical(
                        Map.of(
                            "otpInfo",
                            Map.of(
                                "otpSetup",
                                false,
                                "otpSecret",
                                "SyntheticSetupSecret1234567890",
                                "otpSecretQrCode",
                                "synthetic-qr"),
                            "bootstrapReceipt",
                            sealed,
                            "authorityValidUntil",
                            deadline));
                return withSuccess(result, MediaType.APPLICATION_JSON).createResponse(req);
              });
    }

    void expectApp() {
      server
          .expect(
              requestTo(
                  org.springframework.web.util.UriComponentsBuilder.fromUriString(issuer.base())
                      .pathSegment(
                          "realms", "synthetic", "otp-config", "setup-otp", pending.username())
                      .build()
                      .encode()
                      .toUri()))
          .andExpect(method(HttpMethod.PUT))
          .andExpect(header("X-ORISO-OTP-Bootstrap", prepareReceipt))
          .andRespond(
              req -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertNull(req.getHeaders().getFirst("X-ORISO-Human-Authorization"));
                var actual = (org.springframework.mock.http.client.MockClientHttpRequest) req;
                JsonNode body = json.read(actual.getBodyAsString(), JsonNode.class);
                assertEquals("123456", body.path("otp").path("initialCode").asText());
                assertEquals(
                    "SyntheticSetupSecret1234567890", body.path("otp").path("secret").asText());
                assertEquals(
                    "INVITATION", body.path("target").path("origin").path("kind").asText());
                assertEquals(
                    json.digest("otp-bootstrap-state/v1", prepareReceipt),
                    body.path("expectedState").asText());
                assertEquals(
                    1,
                    jdbc.queryForObject(
                        "SELECT COUNT(*) FROM protected_otp_bootstrap_operation WHERE state='FROZEN'",
                        Integer.class));
                ObjectNode materialized = body.deepCopy();
                materialized.put("username", pending.username());
                materialized.put("bootstrapReceipt", prepareReceipt);
                var facts = prepareFacts.deepCopy();
                facts.put("state", "COMPLETE");
                facts.set("operationId", body.path("operationId"));
                facts.put("intentDigest", json.digest("otp-bootstrap-intent/v1", materialized));
                facts.put("status", 201);
                var nativeResult = Map.of("info", "OTP credential created");
                facts.put("resultDigest", json.digest("otp-result", nativeResult));
                String encoded =
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(json.canonical(facts).getBytes(StandardCharsets.UTF_8));
                String receipt = nativeReceipt(key, "otp-bootstrap-complete/v1", encoded);
                return withCreatedEntity(java.net.URI.create("/synthetic"))
                    .body(
                        json.canonical(
                            Map.of("status", 201, "result", nativeResult, "receipt", receipt)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .createResponse(req);
              });
    }

    private static String nativeReceipt(byte[] key, String domain, String encoded) {
      try {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
        return encoded
            + "."
            + HexFormat.of()
                .formatHex(mac.doFinal((domain + "\n" + encoded).getBytes(StandardCharsets.UTF_8)));
      } catch (java.security.GeneralSecurityException failure) {
        throw new IllegalStateException(failure);
      }
    }

    @Override
    public void close() {
      issuer.close();
    }
  }
}
