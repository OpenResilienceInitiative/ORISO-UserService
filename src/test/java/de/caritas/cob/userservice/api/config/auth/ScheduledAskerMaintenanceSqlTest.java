package de.caritas.cob.userservice.api.config.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionLifecycleService;
import de.caritas.cob.userservice.api.workflow.delete.service.ScheduledAskerMaintenance;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** Real SQL/receipt/HTTP scheduler; actor doubles are explicit except the real issuer case. */
class ScheduledAskerMaintenanceSqlTest {
  private static final String FIRST = "00000000-0000-4000-8000-000000000001";
  private static final String SECOND = "00000000-0000-4000-8000-000000000002";
  private static final String SUBJECT = "091b8cef-09a8-4e5b-a244-6be3db4b7bee";
  private static final LocalDateTime DELETION = LocalDateTime.of(2020, 1, 1, 0, 0);

  @Test
  void firstMissingNativeAccountCannotBlockLaterOwnedAccountOrEraseEitherLocalRow()
      throws Exception {
    try (var f = new Fixture()) {
      f.user(FIRST);
      f.user(SECOND);
      f.readFailure(FIRST, HttpStatus.NOT_FOUND);
      f.success(SECOND);
      assertDoesNotThrow(() -> f.scheduler().performDeletionWorkflow());
      assertEquals("PREPARED", f.status(FIRST));
      assertEquals("ACKNOWLEDGED", f.status(SECOND));
      assertEquals(2, f.jdbc.queryForObject("SELECT COUNT(*) FROM user", Integer.class));
      assertNotNull(f.lastAttempt(FIRST));
      assertNotNull(f.lastAttempt(SECOND));
      verify(f.claims).release(f.lease);
      f.server.verify();
    }
  }

  @Test
  void unattemptedAccountRunsBeforePersistentlyRefusedEarlierId() throws Exception {
    try (var f = new Fixture()) {
      f.user(FIRST);
      f.user(SECOND);
      f.previousAttempt(FIRST);
      f.success(SECOND);
      f.readFailure(FIRST, HttpStatus.FORBIDDEN);
      assertDoesNotThrow(() -> f.scheduler().performDeletionWorkflow());
      assertEquals("ACKNOWLEDGED", f.status(SECOND));
      assertEquals("PREPARED", f.status(FIRST));
      f.server.verify();
    }
  }

  @Test
  void lostDeleteReplyRetriesExactFrozenIntentWithoutReadingOrInventingAnAcknowledgement()
      throws Exception {
    try (var f = new Fixture()) {
      f.user(FIRST);
      f.readSuccess(FIRST);
      f.server.expect(requestTo(f.url(FIRST, "delete"))).andRespond(withServerError());
      assertDoesNotThrow(() -> f.scheduler().performDeletionWorkflow());
      assertEquals("FROZEN", f.status(FIRST));
      String frozen = f.frozen(FIRST);
      assertNotNull(frozen);
      assertNull(
          f.jdbc.queryForObject(
              "SELECT receipt_json FROM identity_maintenance_intent WHERE subject_id=?",
              String.class,
              FIRST));
      f.server.verify();
      f.server.reset();
      f.server
          .expect(requestTo(f.url(FIRST, "delete")))
          .andExpect(content().string(frozen))
          .andRespond(request -> f.reply((MockClientHttpRequest) request));
      assertDoesNotThrow(() -> f.scheduler().performDeletionWorkflow());
      assertEquals("ACKNOWLEDGED", f.status(FIRST));
      assertEquals(frozen, f.frozen(FIRST));
      verify(f.claims, times(2)).release(f.lease);
      f.server.verify();
    }
  }

  @Test
  void malformedReceiptRemainsUnacknowledgedWhileNextAccountStillFinishes() throws Exception {
    try (var f = new Fixture()) {
      f.user(FIRST);
      f.user(SECOND);
      f.readSuccess(FIRST);
      f.server
          .expect(requestTo(f.url(FIRST, "delete")))
          .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
      f.success(SECOND);
      assertDoesNotThrow(() -> f.scheduler().performDeletionWorkflow());
      assertEquals("FROZEN", f.status(FIRST));
      assertEquals("ACKNOWLEDGED", f.status(SECOND));
      f.server.verify();
    }
  }

  @Test
  void schedulerUsesRealCheckedAdminIssuerWithoutReopeningGenericIssuance() throws Exception {
    try (var issuer = new SyntheticTaskIssuer()) {
      issuer.adminSubject = SUBJECT;
      var env = FiveActorConfigurationTest.environment();
      env.setProperty("IDENTITY_ACCOUNT_ADMIN_SERVICE_SUBJECT", SUBJECT);
      var actors = FiveActorConfigurationTest.configured(env);
      var identity = new IdentityConfig();
      identity.setOpenidConnectUrl(issuer.base());
      var authentication =
          new KeycloakAuthClient(new RestTemplate(), new AuthenticatedUser(), identity);
      ReflectionTestUtils.setField(authentication, "keycloakClientId", "app");
      var keycloak = new KeycloakConfig();
      keycloak.setAuthServerUrl(issuer.base());
      keycloak.setRealm("synthetic");
      var decoder = NimbusJwtDecoder.withJwkSetUri(issuer.base() + "/certs").build();
      var broker = new TaskActorTokenBroker(actors, authentication, decoder, keycloak);
      try (var f = new Fixture(actors, broker)) {
        f.user(FIRST);
        f.success(FIRST);
        assertDoesNotThrow(() -> f.scheduler().performDeletionWorkflow());
        assertEquals("ACKNOWLEDGED", f.status(FIRST));
        assertEquals(2, issuer.calls.get());
        assertThrows(
            IllegalStateException.class, () -> broker.issue(TaskPurpose.ACCOUNT_MAINTENANCE));
        assertEquals(2, issuer.calls.get());
        f.server.verify();
      }
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final byte[] key = new byte[32];
    private final AccountLifecycleCodec codec = new AccountLifecycleCodec(key);
    private final Clock clock = Clock.systemUTC();
    private final JdbcDataSource source = new JdbcDataSource();
    private final JdbcTemplate jdbc;
    private final DataSourceTransactionManager transactions;
    private final ScheduledTaskClaimService claims = mock(ScheduledTaskClaimService.class);
    private final ScheduledTaskClaimService.ClaimLease lease =
        new ScheduledTaskClaimService.ClaimLease(
            "account-deletion",
            LocalDateTime.now(clock)
                .plusHours(1)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    private final RestTemplate http = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    private final TaskActorConfiguration actors;
    private final TaskActorTokenBroker broker;

    Fixture() throws Exception {
      this(mock(TaskActorConfiguration.class), mock(TaskActorTokenBroker.class));
      var credentials =
          new TaskActorCredentials(
              TaskActor.ACCOUNT_ADMIN, "backend-account-admin", "synthetic-secret", SUBJECT);
      when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(credentials);
      var checked = mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
      when(checked.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);
      when(checked.accessToken()).thenReturn("synthetic-checked-bearer");
      when(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(checked);
      // Allows the independent fairness counterfactual to reach the original per-account defect.
      when(broker.issue(TaskPurpose.ACCOUNT_MAINTENANCE))
          .thenReturn(
              new TaskActorTokenBroker.TaskToken(
                  TaskActor.ACCOUNT_ADMIN, "synthetic-checked-bearer", 300));
    }

    Fixture(TaskActorConfiguration actors, TaskActorTokenBroker broker) throws Exception {
      this.actors = actors;
      this.broker = broker;
      source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
      jdbc = new JdbcTemplate(source);
      transactions = new DataSourceTransactionManager(source);
      jdbc.execute(
          "CREATE TABLE user(user_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT,delete_date DATETIME(6),"
              + "deletion_lifecycle_state VARCHAR(64),deletion_read_only_until DATETIME(6),"
              + "deletion_paused_until DATETIME(6))");
      jdbc.execute(
          "CREATE TABLE scheduled_task_claim(task_name VARCHAR(255) PRIMARY KEY,claimed_until DATETIME(6))");
      for (String migration :
          List.of("20261021_identity_maintenance_367", "20261022_identity_maintenance_retry_367")) {
        for (String sql :
            Files.readString(
                    Path.of(
                        "src/main/resources/db/changelog/changeset/" + migration + "/migrate.sql"))
                .split(";")) if (!sql.isBlank()) jdbc.execute(sql);
      }
      // DATETIME(6) has microsecond precision, matching the persisted scheduler lease contract.
      var until = lease.claimedUntil().withNano((lease.claimedUntil().getNano() / 1000) * 1000);
      jdbc.update("INSERT INTO scheduled_task_claim VALUES(?,?)", lease.taskName(), until);
      when(claims.tryClaimLease(eq("account-deletion"), any()))
          .thenReturn(
              Optional.of(new ScheduledTaskClaimService.ClaimLease(lease.taskName(), until)));
    }

    ScheduledAskerMaintenance scheduler() {
      return new ScheduledAskerMaintenance(
          jdbc,
          transactions,
          claims,
          clock,
          new DeletionLifecycleService(),
          actors,
          broker,
          codec,
          http,
          "https://identity.example.test",
          "synthetic",
          "synthetic-realm");
    }

    void user(String account) {
      jdbc.update(
          "INSERT INTO user(user_id,tenant_id,delete_date,deletion_lifecycle_state,"
              + "deletion_read_only_until) VALUES(?,7,?,'READ_ONLY_SAFEGUARD',?)",
          account,
          DELETION,
          DELETION.plusHours(48));
    }

    void previousAttempt(String account) {
      var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
      jdbc.update(
          "INSERT INTO identity_maintenance_intent(operation_id,realm_id,realm_name,subject_type,"
              + "subject_id,deletion_started_at,tenant_id,claim_token,claim_until,status,"
              + "owner_client,owner_subject,last_attempt_at) VALUES(?,?,?,'ASKER',?,?,7,?,?,'PREPARED',?,?,?)",
          UUID.randomUUID().toString(),
          "synthetic-realm",
          "synthetic",
          account,
          DELETION,
          UUID.randomUUID().toString(),
          lease.claimedUntil(),
          actor.clientId(),
          actor.serviceSubject(),
          LocalDateTime.now(clock).minusMinutes(1));
      jdbc.update(
          "UPDATE user SET deletion_lifecycle_state='HARD_DELETE_IN_PROGRESS' WHERE user_id=?",
          account);
    }

    String url(String account, String command) {
      return "https://identity.example.test/realms/synthetic/oriso-account-maintenance/v1/accounts/"
          + account
          + "/"
          + command;
    }

    void readFailure(String account, HttpStatus status) {
      server.expect(requestTo(url(account, "read"))).andRespond(withStatus(status));
    }

    void readSuccess(String account) {
      var value = new TreeMap<String, Object>();
      value.put("id", account);
      value.put("username", "synthetic-" + account);
      for (String field : List.of("email", "firstName", "lastName")) value.put(field, null);
      value.put("enabled", false);
      value.put("emailVerified", false);
      value.put("tenantId", "7");
      value.put("preferredLanguage", "de");
      value.put("effectiveRoles", List.of("user"));
      value.put("passwordChangeRequired", false);
      value.put("state", "a".repeat(64));
      server
          .expect(requestTo(url(account, "read")))
          .andExpect(method(HttpMethod.POST))
          .andRespond(withSuccess(codec.canonical(value), MediaType.APPLICATION_JSON));
    }

    void success(String account) {
      readSuccess(account);
      server
          .expect(requestTo(url(account, "delete")))
          .andExpect(method(HttpMethod.POST))
          .andRespond(request -> reply((MockClientHttpRequest) request));
    }

    org.springframework.http.client.ClientHttpResponse reply(MockClientHttpRequest request)
        throws java.io.IOException {
      try {
        JsonNode frozen = codec.read(request.getBodyAsString(), JsonNode.class);
        var actor = actors.require(TaskPurpose.ACCOUNT_MAINTENANCE);
        var outcome = new TreeMap<String, Object>();
        outcome.put("realmId", "synthetic-realm");
        outcome.put("accountId", frozen.path("target").path("accountId").asText());
        outcome.put("operationId", frozen.path("operationId").asText());
        outcome.put("command", "delete");
        outcome.put("ownerClient", actor.clientId());
        outcome.put("ownerSubject", actor.serviceSubject());
        outcome.put("intentDigest", codec.digest("maintenance-intent", frozen));
        outcome.put("previousState", frozen.path("expectedState").asText());
        outcome.put("tenantId", "7");
        outcome.put(
            "originDigest",
            codec.digest("maintenance-origin", frozen.path("target").path("origin")));
        outcome.put("result", "DELETED");
        String encoded =
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(codec.canonical(outcome).getBytes(StandardCharsets.UTF_8));
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        String receipt =
            encoded
                + "."
                + HexFormat.of()
                    .formatHex(
                        mac.doFinal(
                            ("maintenance-operation-receipt\n" + encoded)
                                .getBytes(StandardCharsets.UTF_8)));
        String response =
            codec.canonical(
                Map.of(
                    "operationId",
                    frozen.path("operationId").asText(),
                    "accountId",
                    frozen.path("target").path("accountId").asText(),
                    "result",
                    "DELETED",
                    "receipt",
                    receipt));
        return withSuccess(response, MediaType.APPLICATION_JSON).createResponse(request);
      } catch (Exception failure) {
        throw new java.io.IOException("Synthetic native receipt unavailable", failure);
      }
    }

    String status(String account) {
      return jdbc.queryForObject(
          "SELECT status FROM identity_maintenance_intent WHERE subject_id=?",
          String.class,
          account);
    }

    String frozen(String account) {
      return jdbc.queryForObject(
          "SELECT frozen_body FROM identity_maintenance_intent WHERE subject_id=?",
          String.class,
          account);
    }

    LocalDateTime lastAttempt(String account) {
      return jdbc.queryForObject(
          "SELECT last_attempt_at FROM identity_maintenance_intent WHERE subject_id=?",
          LocalDateTime.class,
          account);
    }

    @Override
    public void close() {
      jdbc.execute("SHUTDOWN");
    }
  }
}
