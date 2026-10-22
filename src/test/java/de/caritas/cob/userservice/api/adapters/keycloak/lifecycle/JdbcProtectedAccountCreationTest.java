package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.AccountCreation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real H2 + actual migrations/acceptance claim. Native crypto uses independent synthetic vectors.
 */
class JdbcProtectedAccountCreationTest {
  private static final String TOKEN = "b".repeat(64);
  private final Clock clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager manager;
  private JdbcProtectedInvitationJournal journal;
  private JdbcProtectedInvitationAcceptance acceptance;
  private AccountLifecycleCodec json;
  private ProtectedAccountCodec accounts;
  private JsonNode vector;
  private AcceptanceClaim claim;

  @BeforeEach
  void actualOwningDatabaseAndFrozenNativeVector(org.junit.jupiter.api.TestInfo info)
      throws Exception {
    boolean reserved = info.getTestMethod().orElseThrow().getName().startsWith("reservedTenant");
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:creation-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    jdbc = new JdbcTemplate(source);
    manager = new DataSourceTransactionManager(source);
    jdbc.execute(
        "CREATE TABLE account_invite(id BIGINT PRIMARY KEY,target_role VARCHAR(64),tenant_id BIGINT,recipient_email VARCHAR(255),purpose VARCHAR(32),status VARCHAR(32),expires_at TIMESTAMP,token_hash VARCHAR(64),agency_id BIGINT,tenant_id_reservation_token VARCHAR(36))");
    for (String name :
        new String[] {
          "20261022_protected_invitation_367",
          "20261022_protected_invitation_acceptance_367",
          "20261022_protected_account_creation_367"
        }) {
      for (String sql :
          Files.readString(
                  Path.of("src/main/resources/db/changelog/changeset/" + name + "/migrate.sql"))
              .split(";")) {
        if (!sql.isBlank()) jdbc.execute(sql);
      }
    }
    byte[] key =
        HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    json = new AccountLifecycleCodec(key);
    vector =
        json.read(
            Files.readString(Path.of("src/test/resources/scenario-c/protected-account-v1.json")),
            JsonNode.class);
    var approvals =
        new ProtectedInvitationCodec(
            key, clock, vector.path("realmName").asText(), vector.path("realmId").asText());
    accounts =
        new ProtectedAccountCodec(
            key,
            clock,
            approvals,
            vector.path("realmName").asText(),
            vector.path("realmId").asText(),
            vector.path("realmIssuer").asText());
    var av =
        reserved
            ? json.read(
                Files.readString(
                    Path.of("src/test/resources/scenario-c/protected-approval-reserved-v1.json")),
                JsonNode.class)
            : vector.path("approvalVector");
    var frozen =
        approvals.freeze(
            UUID.fromString(av.path("approvalId").asText()),
            json.canonical(av.path("request")),
            av.path("issuerRealm").asText(),
            av.path("issuerSubject").asText());
    jdbc.update(
        "INSERT INTO account_invite VALUES(73,'TENANT_ADMIN',7,'ada@example.test','INVITE','EMAIL_SENT',?,?,?,?)",
        Timestamp.from(Instant.parse("2030-01-02T03:04:05Z")),
        TOKEN,
        reserved ? 11L : null,
        reserved ? av.path("syntheticReservationToken").asText() : null);
    journal = new JdbcProtectedInvitationJournal(jdbc, manager, approvals, json, clock);
    journal.propose(73, frozen);
    journal.recordReady(
        frozen.approvalId(),
        approvals.verifyAuthorized(frozen, json.canonical(av.path("response"))));
    acceptance =
        new JdbcProtectedInvitationAcceptance(jdbc, manager, journal, approvals, json, clock);
    acceptance.claim(73, TOKEN, vector.path("registration"));
    // Fix only these synthetic SQL identity values to the independently signed vector.
    // Re-read through the actual acceptance owner; no public constructor or mock claim.
    jdbc.update(
        "UPDATE protected_invitation_acceptance SET claim_id=?,attempt_id=?,execution_id=? WHERE invite_id=73",
        vector.path("claimId").asText(),
        vector.path("attemptId").asText(),
        vector.path("executionId").asText());
    claim = acceptance.claim(73, TOKEN, vector.path("registration"));
  }

  @Test
  void originalPreparedMetadataIsCommittedBeforeRpcWithoutPasswordOrAccountBody() {
    var prepared = prepared();
    assertDoesNotThrow(() -> owner().prepare(claim, prepared));
    assertEquals(1, count("protected_account_creation"));
    assertEquals(
        "PREPARED",
        jdbc.queryForObject("SELECT state FROM protected_account_creation", String.class));
    String saved =
        jdbc.queryForObject(
            "SELECT prepared_checkpoint FROM protected_account_creation", String.class);
    String plain =
        new String(Base64.getUrlDecoder().decode(saved.split("\\.")[0]), StandardCharsets.UTF_8);
    assertFalse(plain.contains(account().password()));
    assertFalse(plain.contains("\"password\""));
    assertFalse(plain.contains("\"body\""));
    assertFalse(plain.contains("\"account\""));
    assertEquals(prepared.creationFingerprint(), owner().metadata(claim).creationFingerprint());
    assertEquals(0, count("protected_account_recovery"));
  }

  @Test
  void restartKeepsTheOriginalMetadataAndEachSavedRecoveryIdBeforeRpc() {
    assertDoesNotThrow(() -> owner().prepare(claim, prepared()));
    String original =
        jdbc.queryForObject(
            "SELECT prepared_checkpoint FROM protected_account_creation", String.class);
    assertDoesNotThrow(() -> owner().prepare(claim, prepared()));
    assertEquals(
        original,
        jdbc.queryForObject(
            "SELECT prepared_checkpoint FROM protected_account_creation", String.class));
    var first = owner().recovery(claim, 0);
    var replay = owner().recovery(claim, 0);
    assertEquals(first.recoveryId(), replay.recoveryId());
    assertEquals(first.approvalId(), replay.approvalId());
    assertEquals(first.attemptId(), replay.attemptId());
    assertEquals(1, count("protected_account_recovery"));
    assertArrayEquals(accounts.recoverBody(first), accounts.recoverBody(replay));
    jdbc.update("UPDATE account_invite SET status='REVOKED',token_hash=NULL WHERE id=73");
    var historical = assertDoesNotThrow(() -> acceptance.historical(73));
    var reducing = assertDoesNotThrow(() -> owner().recovery(historical, 0));
    assertEquals(first.recoveryId(), reducing.recoveryId());
    assertArrayEquals(accounts.recoverBody(first), accounts.recoverBody(reducing));
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> owner().metadata(claim));
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> owner().recovery(claim, 0));
    jdbc.update(
        "UPDATE protected_invitation_acceptance SET execution_id=? WHERE invite_id=73",
        UUID.randomUUID().toString());
    assertThrows(IllegalStateException.class, () -> owner().recovery(historical, 0));
    assertEquals(1, count("protected_account_recovery"));
    assertThrows(IllegalArgumentException.class, () -> owner().recovery(claim, -1));
    assertThrows(IllegalArgumentException.class, () -> owner().recovery(claim, Long.MAX_VALUE));
  }

  @Test
  void onlyAnExactVerifiedCreatedResultBecomesDurableOwnershipAcrossRestart() throws Exception {
    var prepared = prepared();
    assertDoesNotThrow(() -> owner().prepare(claim, prepared));
    assertThrows(IllegalStateException.class, () -> owner().created(claim));
    var created =
        accounts.verifyCreated(
            prepared,
            json.canonical(vector.path("createdResponse")).getBytes(StandardCharsets.UTF_8));
    assertDoesNotThrow(() -> owner().recordCreated(claim, created));
    var wrongAccount =
        new AccountCreation(
            account().username(),
            account().email(),
            "Other",
            account().lastName(),
            account().preferredLanguage(),
            account().password(),
            false);
    var foreignPrepared = accounts.prepare(claim, vector.path("registration"), wrongAccount);
    assertThrows(IllegalStateException.class, () -> owner().prepare(claim, foreignPrepared));
    var foreignResponse =
        (com.fasterxml.jackson.databind.node.ObjectNode) vector.path("createdResponse").deepCopy();
    var payload =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            vector.path("creationProofPayload").deepCopy();
    payload.put("creationFingerprint", foreignPrepared.creationFingerprint());
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.canonical(payload).getBytes(StandardCharsets.UTF_8));
    var mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(
        new javax.crypto.spec.SecretKeySpec(
            HexFormat.of()
                .parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),
            "HmacSHA256"));
    foreignResponse.put(
        "creationProof",
        encoded
            + "."
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    mac.doFinal(("creation-proof\n" + encoded).getBytes(StandardCharsets.UTF_8))));
    var foreignCreated =
        assertDoesNotThrow(
            () ->
                accounts.verifyCreated(
                    foreignPrepared,
                    json.canonical(foreignResponse).getBytes(StandardCharsets.UTF_8)));
    assertThrows(IllegalStateException.class, () -> owner().recordCreated(claim, foreignCreated));
    var restored = owner().created(claim);
    assertEquals(created.accountId(), restored.accountId());
    assertEquals(created.creationProof(), restored.creationProof());
    assertEquals(
        "CREATED",
        jdbc.queryForObject("SELECT state FROM protected_account_creation", String.class));
    assertDoesNotThrow(() -> owner().recordCreated(claim, created));
    assertEquals(1, count("protected_account_creation"));
    assertFalse(
        jdbc.queryForMap("SELECT * FROM protected_account_creation")
            .toString()
            .contains(account().password()));
    jdbc.update("UPDATE account_invite SET status='REVOKED',token_hash=NULL WHERE id=73");
    var historical = acceptance.historical(73);
    var owned = journal.withHistoricalInvite(73,
        () -> owner().createdWithinOwner(historical)).orElseThrow();
    assertEquals(created.accountId(), owned.accountId());
    assertEquals(created.creationProof(), owned.creationProof());
  }

  @Test
  void changedCurrentRecipientTokenOrClaimTupleCannotReadOrRewriteTheSavedAttempt() {
    assertDoesNotThrow(() -> owner().prepare(claim, prepared()));
    String original =
        jdbc.queryForObject(
            "SELECT prepared_checkpoint FROM protected_account_creation", String.class);
    var changedRegistration = vector.path("registration").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changedRegistration)
        .put("password", "different synthetic password");
    assertThrows(
        IllegalArgumentException.class,
        () -> accounts.prepare(claim, changedRegistration, account()));
    jdbc.update("UPDATE account_invite SET token_hash=? WHERE id=73", "c".repeat(64));
    assertThrows(
        IllegalStateException.class,
        () -> owner().metadata(claim));
    assertThrows(
        IllegalStateException.class,
        () -> owner().recovery(claim, 0));
    jdbc.update("UPDATE account_invite SET token_hash=? WHERE id=73", TOKEN);
    jdbc.update(
        "UPDATE protected_invitation_acceptance SET attempt_id=? WHERE invite_id=73",
        UUID.randomUUID().toString());
    assertThrows(IllegalStateException.class, () -> owner().prepare(claim, prepared()));
    assertEquals(
        original,
        jdbc.queryForObject(
            "SELECT prepared_checkpoint FROM protected_account_creation", String.class));
    assertEquals(0, count("protected_account_recovery"));
  }

  @Test
  void historicalOwnerCanAuthenticateLostReplyMetadataWithoutInventingCreatedOwnership() {
    var prepared = prepared();
    owner().prepare(claim, prepared);
    jdbc.update("UPDATE account_invite SET status='REVOKED',token_hash=NULL WHERE id=73");
    var historical = acceptance.historical(73);
    assertThrows(IllegalStateException.class, () -> owner().metadataWithinOwner(historical));
    assertThrows(IllegalStateException.class, () -> owner().createdWithinOwner(historical));
    var metadata =
        assertDoesNotThrow(
            () -> journal.withHistoricalInvite(73, () -> owner().metadataWithinOwner(historical)));
    assertEquals(prepared.creationFingerprint(), metadata.creationFingerprint());
    assertTrue(
        journal.withHistoricalInvite(73, () -> owner().createdWithinOwner(historical)).isEmpty());
    assertEquals(
        "PREPARED",
        jdbc.queryForObject("SELECT state FROM protected_account_creation", String.class));
    assertNull(
        jdbc.queryForObject(
            "SELECT created_checkpoint FROM protected_account_creation", String.class));
    assertEquals(0, count("protected_account_recovery"));
    var other = new JdbcDataSource();
    other.setURL("jdbc:h2:mem:foreign-owner-" + UUID.randomUUID());
    assertThrows(IllegalStateException.class,
        () -> new TransactionTemplate(new DataSourceTransactionManager(other)).execute(
            tx -> owner().metadataWithinOwner(historical)));
    jdbc.update(
        "UPDATE protected_invitation_acceptance SET execution_id=? WHERE invite_id=73",
        UUID.randomUUID().toString());
    assertThrows(
        IllegalStateException.class,
        () -> journal.withHistoricalInvite(73, () -> owner().metadataWithinOwner(historical)));
  }

  @Test
  void ambientSqlCannotPretendTheOriginalIntentIsAlreadyDurable() {
    new TransactionTemplate(manager)
        .execute(
            tx -> {
              assertThrows(IllegalStateException.class, () -> owner().prepare(claim, prepared()));
              assertThrows(IllegalStateException.class, () -> owner().metadata(claim));
              assertThrows(IllegalStateException.class, () -> owner().recovery(claim, 0));
              return null;
            });
    assertEquals(0, count("protected_account_creation"));
  }

  @Test
  void anotherPhysicalDataSourceCannotBorrowTheJournalTransactionOwner() {
    var foreign = new JdbcDataSource();
    foreign.setURL("jdbc:h2:mem:foreign-" + UUID.randomUUID());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JdbcProtectedAccountCreation(
                jdbc, new DataSourceTransactionManager(foreign), journal, accounts, json, clock));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JdbcProtectedAccountCreation(
                new JdbcTemplate(foreign),
                new DataSourceTransactionManager(foreign),
                journal,
                accounts,
                json,
                clock));
  }

  @Test
  void reservedTenantOriginalServerDtoAndVersionSurviveRestartWithoutCapabilityOrPasswordAtRest() {
    assertTrue(assertDoesNotThrow(() -> owner().findPrepared(claim)).isEmpty());
    assertDoesNotThrow(() -> owner().prepare(claim, prepared()));
    assertTrue(owner().findPrepared(claim).isPresent());
    assertTrue(owner().tenantRequest(claim).isEmpty());
    var original = tenantRequest();
    var frozen = assertDoesNotThrow(() -> owner().freezeTenantRequest(claim, original));
    assertEquals("Original tenant", frozen.getName());
    String stored =
        jdbc.queryForObject(
            "SELECT tenant_request_json FROM protected_account_creation", String.class);
    assertFalse(stored.contains(original.getTenantIdReservationToken()));
    assertFalse(stored.contains(account().password()));
    original.setName("later caller mutation");
    var resumed = owner().tenantRequest(claim).orElseThrow();
    assertEquals("Original tenant", resumed.getName());
    assertEquals("2030-01-01T00:00:00Z", resumed.getOnboardingDpaAcceptance().getDpaVersion());
    assertEquals(
        tenantRequest().getTenantIdReservationToken(), resumed.getTenantIdReservationToken());
    assertDoesNotThrow(() -> owner().freezeTenantRequest(claim, tenantRequest()));
    var newlyPublished = tenantRequest();
    newlyPublished.getOnboardingDpaAcceptance().setDpaVersion("2030-01-02T00:00:00Z");
    assertThrows(
        IllegalStateException.class, () -> owner().freezeTenantRequest(claim, newlyPublished));
    assertEquals(
        stored,
        jdbc.queryForObject(
            "SELECT tenant_request_json FROM protected_account_creation", String.class));
  }

  @Test
  void reservedTenantRequestRequiresTheSameHeldPairAndAuthenticOriginalFullBody() {
    assertDoesNotThrow(() -> owner().prepare(claim, prepared()));
    assertDoesNotThrow(() -> owner().freezeTenantRequest(claim, tenantRequest()));
    var foreign = tenantRequest();
    foreign.setId(99L);
    assertThrows(IllegalStateException.class, () -> owner().freezeTenantRequest(claim, foreign));
    String cap = tenantRequest().getTenantIdReservationToken();
    jdbc.update(
        "UPDATE account_invite SET tenant_id_reservation_token=? WHERE id=73",
        UUID.randomUUID().toString());
    assertThrows(RuntimeException.class, () -> owner().tenantRequest(claim));
    jdbc.update("UPDATE account_invite SET tenant_id_reservation_token=? WHERE id=73", cap);
    String stored =
        jdbc.queryForObject(
            "SELECT tenant_request_json FROM protected_account_creation", String.class);
    var altered =
        (com.fasterxml.jackson.databind.node.ObjectNode) json.read(stored, JsonNode.class);
    altered.put("name", "other SQL body");
    jdbc.update(
        "UPDATE protected_account_creation SET tenant_request_json=? WHERE invite_id=73",
        json.canonical(altered));
    assertThrows(IllegalStateException.class, () -> owner().tenantRequest(claim));
    assertEquals(1, count("protected_account_creation"));
  }

  private de.caritas.cob.userservice.tenantadminservice.generated.web.model.MultilingualTenantDTO
      tenantRequest() {
    String cap =
        jdbc.queryForObject(
            "SELECT tenant_id_reservation_token FROM account_invite WHERE id=73", String.class);
    return new de.caritas.cob.userservice.tenantadminservice.generated.web.model
            .MultilingualTenantDTO()
        .id(7L)
        .name("Original tenant")
        .tenantIdReservationToken(cap)
        .adminEmails(java.util.List.of("ada@example.test"))
        .onboardingDpaAcceptance(
            new de.caritas.cob.userservice.tenantadminservice.generated.web.model
                    .OnboardingDpaAcceptanceDTO()
                .accepted(true)
                .dpaVersion("2030-01-01T00:00:00Z")
                .signerUserId("f6e71c6d-93f8-4a4e-8be4-e3c10994e9d1")
                .signerUsername("enc.synthetic"));
  }

  private JdbcProtectedAccountCreation owner() {
    return new JdbcProtectedAccountCreation(jdbc, manager, journal, accounts, json, clock);
  }

  private AccountCreation account() {
    return json.read(vector.path("account").toString(), AccountCreation.class);
  }

  private ProtectedAccountCodec.PreparedCreation prepared() {
    return accounts.prepare(claim, vector.path("registration"), account());
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }
}
