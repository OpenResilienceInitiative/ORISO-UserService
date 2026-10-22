package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakCustomConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.AccountCreation;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService;
import de.caritas.cob.userservice.api.service.accountinvite.*;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.codec.binary.Base32;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.support.*;
import org.springframework.web.client.*;

/** Hosted-only serial SQL/native cleanup pair; no in-flight SMTP/concurrency proof is claimed. */
class ProtectedAccountCleanupNativePairProof {
  private static String required(String name) {
    String value = System.getenv(name);
    assertTrue(value != null && !value.isBlank(), "Required isolated cleanup pair input absent");
    return value;
  }

  @Test
  void lostCreationAndCompensationRepliesRequireIndependentNativeFinalStatusBeforeRestartRelease() throws Exception {
    assertEquals("true", required("GITHUB_ACTIONS"));
    assertEquals("Linux", System.getProperty("os.name"));
    String base = required("PAIR_NATIVE_BASE"), realm = required("PAIR_REALM");
    assertTrue(base.matches("http://127\\.0\\.0\\.1:[0-9]+"));
    String realmId = required("PAIR_REALM_ID");
    String logicalBase = "http://protected-fixture.invalid";
    String issuer = logicalBase + "/realms/" + realm;
    byte[] key = Base64.getDecoder().decode(required("PAIR_CONTEXT_KEY_B64"));
    var codec = new AccountLifecycleCodec(key);
    var requestBody = codec.read(required("PAIR_APPROVAL_REQUEST"), JsonNode.class);
    var approved = codec.read(required("PAIR_APPROVAL_RESPONSE"), JsonNode.class);
    var scope = requestBody.path("scope");
    assertEquals("73", scope.path("inviteId").asText());
    String recipient = scope.path("recipient").asText();
    var clock = Clock.systemUTC();
    var approvals = new ProtectedInvitationCodec(key, clock, realm, realmId);
    var frozen = approvals.freeze(UUID.fromString(approved.path("approvalId").asText()),
        codec.canonical(requestBody), realmId, approved.path("approvalReceipt").path("issuerSubject").asText());
    var source = new DriverManagerDataSource("jdbc:h2:mem:cleanup-pair-" + UUID.randomUUID()
        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    var jdbc = new JdbcTemplate(source);
    var manager = new DataSourceTransactionManager(source);
    jdbc.execute("CREATE TABLE account_invite(id BIGINT PRIMARY KEY,target_role VARCHAR(64),tenant_id BIGINT,recipient_email VARCHAR(255),purpose VARCHAR(32),status VARCHAR(32),expires_at TIMESTAMP,token_hash VARCHAR(64),agency_id BIGINT,tenant_id_reservation_token VARCHAR(36),accepted_by_user_id VARCHAR(36),accepted_at TIMESTAMP,provisioning_status VARCHAR(32),update_date TIMESTAMP,active_recipient_key VARCHAR(255))");
    jdbc.execute("CREATE TABLE admin(admin_id VARCHAR(36) PRIMARY KEY)");
    jdbc.execute("CREATE TABLE account_inactivity(identity_id VARCHAR(36) PRIMARY KEY)");
    for (String name : List.of("20261022_protected_invitation_367", "20261022_protected_invitation_cancel_367",
        "20261022_protected_invitation_acceptance_367", "20261022_protected_account_creation_367",
        "20261022_protected_account_activation_367", "20261022_protected_account_cleanup_367",
        "20261022_protected_invitation_cleanup_status_367", "20261022_protected_otp_bootstrap_367")) {
      for (String sql : Files.readString(Path.of("src/main/resources/db/changelog/changeset/" + name + "/migrate.sql")).split(";"))
        if (!sql.isBlank()) jdbc.execute(sql);
    }
    for (String sql : Files.readString(Path.of("src/main/resources/db/changelog/changeset/20261022_protected_otp_bootstrap_367/operations.sql")).split(";"))
      if (!sql.isBlank()) jdbc.execute(sql);
    jdbc.update("INSERT INTO account_invite(id,target_role,tenant_id,recipient_email,purpose,status,expires_at,token_hash,active_recipient_key) VALUES(73,?,0,?,'INVITE','EMAIL_SENT',?,?,'still-held')",
        scope.path("protectedKind").asText(), recipient, Timestamp.from(Instant.parse(scope.path("validUntil").asText())), "c".repeat(64));
    var journal = new JdbcProtectedInvitationJournal(jdbc, manager, approvals, codec, clock);
    // The runner supplies actual human approval; this pair starts at recipient custody.
    journal.propose(73, frozen);
    journal.recordReady(frozen.approvalId(), approvals.verifyAuthorized(frozen, codec.canonical(approved)));
    var registration = codec.read("{\"password\":\"SyntheticOnlyPassword!731\",\"firstName\":\"Protected\",\"lastName\":\"Fixture\"}", JsonNode.class);
    var acceptance = new JdbcProtectedInvitationAcceptance(jdbc, manager, journal, approvals, codec, clock);
    var claim = acceptance.claim(73, "c".repeat(64), registration);
    var http = new RestTemplate();
    var lostCreate = new AtomicInteger();
    var lostCompensate = new AtomicInteger();
    var observedHttp = new ArrayList<Map<String, Object>>();
    http.getInterceptors()
        .add(
            (outbound, bytes, execute) -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              URI logical = outbound.getURI();
              assertEquals("protected-fixture.invalid", logical.getHost());
              var physical =
                  URI.create(
                      base
                          + logical.getRawPath()
                          + (logical.getRawQuery() == null ? "" : "?" + logical.getRawQuery()));
              var response =
                  execute.execute(
                      new HttpRequestWrapper(outbound) {
                        @Override
                        public URI getURI() {
                          return physical;
                        }
                      },
                      bytes);
              if (observedHttp.size() >= 100)
                throw new IllegalStateException("Pair observations exceed bound");
              observedHttp.add(
                  Map.of(
                      "phase", nativePhase(logical.getPath()),
                      "status", response.getStatusCode().value()));
              Files.writeString(
                  Path.of(required("PAIR_HTTP_WITNESS")), codec.canonical(observedHttp));
              if (logical.getPath().endsWith("/accounts") && response.getStatusCode().value() == 201
                  && lostCreate.getAndIncrement() == 0) {
                response.close();
                throw new ResourceAccessException("Synthetic lost create reply");
              }
              if (logical.getPath().endsWith("/compensate") && response.getStatusCode().value() == 200
                  && lostCompensate.getAndIncrement() == 0) {
                response.close();
                throw new ResourceAccessException("Synthetic lost compensation reply");
              }
              return response;
            });
    var decoder =
        NimbusJwtDecoder.withJwkSetUri(base + "/realms/" + realm + "/protocol/openid-connect/certs")
            .build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
    var env = new MockEnvironment().withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS");
    for (var actor : TaskActor.values()) {
      String prefix = "PAIR_" + actor.name(), target = "IDENTITY_" + actor.name();
      env.setProperty(target + "_CLIENT_ID", required(prefix + "_CLIENT"));
      env.setProperty(target + "_CLIENT_SECRET", required(prefix + "_SECRET"));
      env.setProperty(target + "_SERVICE_SUBJECT", required(prefix + "_SUBJECT"));
    }
    var identity = new IdentityConfig();
    identity.setOpenidConnectUrl(issuer + "/protocol/openid-connect");
    var actors = new TaskActorConfiguration(env, new KeycloakCustomConfig(), identity);
    var config = new KeycloakConfig();
    config.setAuthServerUrl(logicalBase);
    config.setRealm(realm);
    var machineAuth =
        new KeycloakAuthClient(
            http, new AuthenticatedUser("unused", "unused", Set.of(), "", Set.of()), identity);
    var broker = new TaskActorTokenBroker(actors, machineAuth, decoder, config);
    var current = broker.issueCheckedMaintenance(TaskPurpose.OTP);
    assertEquals(
        Set.of("userservice", "oriso-account-lifecycle", "oriso-otp"),
        Set.copyOf(decoder.decode(current.accessToken()).getAudience()));
    var denied =
        assertThrows(
            org.springframework.web.client.HttpClientErrorException.class,
            () -> {
              var headers = new HttpHeaders();
              headers.setBearerAuth(current.accessToken());
              http.exchange(
                  logicalBase + "/admin/realms/" + realm + "/users",
                  HttpMethod.GET,
                  new HttpEntity<>(headers),
                  String.class);
            });
    assertEquals(403, denied.getStatusCode().value());
    var nativeCreator = broker.issueCheckedProvisioning(TaskPurpose.ACCOUNT_PROVISIONING);
    var creator = decoder.decode(nativeCreator.accessToken());
    assertEquals(
        actors.require(TaskPurpose.ACCOUNT_PROVISIONING).serviceSubject(), creator.getSubject());
    assertEquals(
        Set.of("userservice", "oriso-account-lifecycle"), Set.copyOf(creator.getAudience()));
    assertEquals(
        actors.require(TaskPurpose.ACCOUNT_PROVISIONING).clientId(),
        creator.getClaimAsString("azp"));
    assertEquals(
        TaskActor.ACCOUNT_CREATE.roles(),
        Set.copyOf((List<?>) ((Map<?, ?>) creator.getClaims().get("realm_access")).get("roles")));
    var accountCodec = new ProtectedAccountCodec(key, clock, approvals, realm, realmId, issuer);
    String username = "enc." + new Base32().encodeAsString(recipient.getBytes(StandardCharsets.UTF_8)).replace('=', '.');
    var account = new AccountCreation(username, recipient, "Protected", "Fixture", "en", registration.path("password").asText(), false);
    var prepared = accountCodec.prepare(claim, registration, account);
    var creation = new JdbcProtectedAccountCreation(jdbc, manager, journal, accountCodec, codec, clock);
    creation.prepare(claim, prepared);
    var bridge = new KeycloakProtectedAccounts(http, actors, broker, accountCodec, logicalBase, realm);
    assertThrows(KeycloakProtectedAccounts.RemoteFailure.class, () -> bridge.create(prepared));
    assertEquals(1, lostCreate.get());
    assertEquals("PREPARED", jdbc.queryForObject("SELECT state FROM protected_account_creation WHERE invite_id=73", String.class));
    assertFalse(jdbc.queryForObject("SELECT prepared_checkpoint FROM protected_account_creation WHERE invite_id=73", String.class).contains(registration.path("password").asText()));
    var restrictions = new ProtectedInvitationRestrictionCodec(approvals, codec, key, clock, realmId, issuer);
    var finalCodec = new ProtectedCleanupStatusCodec(codec, restrictions, accountCodec, key);
    var receiver = new KeycloakProtectedRestrictions(http, actors, broker, approvals, restrictions, logicalBase, realm, finalCodec);
    var cancellation = new JdbcProtectedInvitationCancellation(jdbc, manager, journal, restrictions, codec, receiver,
        id -> { throw new AssertionError("Original proposal must be durable"); }, clock, finalCodec, accountCodec, acceptance, creation);
    cancellation.cancel(73, ProtectedInvitationCancellation.Reason.REVOKED, () -> jdbc.update(
        "UPDATE account_invite SET status='REVOKED',token_hash=NULL WHERE id=73"));
    assertTrue(cancellation.verifiedRestriction(73).cleanupRequired());
    assertThrows(RuntimeException.class, () -> cancellation.withAcknowledged(73, () -> null));
    var request = new MockHttpServletRequest();
    var otp = new ProtectedOtpBootstrap(jdbc, manager, codec, actors, broker, http, request, clock, logicalBase, realm, realmId);
    var activation = new JdbcProtectedAccountActivation(jdbc, manager, journal, accountCodec, codec, otp,
        mock(AdminRepository.class), mock(AccountInactivityEnrollmentService.class), clock, creation);
    var recovery = new ProtectedAccountRecovery(http, actors, broker, accountCodec, logicalBase, realm);
    var coordinator = new ProtectedAdminOnboardingService(mock(AccountInviteService.class), actors, acceptance,
        accountCodec, creation, bridge, otp, activation, mock(AccountInactivityEnrollmentService.class),
        mock(UsernameTranscoder.class), mock(ProtectedInvitationProfiles.class),
        mock(OperatorDpaContentClient.class), mock(TenantCreationClient.class), clock, recovery, cancellation);
    assertThrows(KeycloakProtectedAccounts.RemoteFailure.class, () -> coordinator.reconcileTerminal(73));
    assertEquals(1, lostCompensate.get());
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM protected_account_recovery", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM protected_account_cleanup", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM protected_invitation_cleanup_status WHERE final_status_json IS NOT NULL", Integer.class));
    assertEquals("still-held", jdbc.queryForObject("SELECT active_recipient_key FROM account_invite WHERE id=73", String.class));
    coordinator.reconcileTerminal(73);
    String firstAck = jdbc.queryForObject("SELECT response_json FROM protected_invitation_cancellation WHERE invite_id=73", String.class);
    var restarted = new JdbcProtectedInvitationCancellation(jdbc, manager, journal, restrictions, codec, receiver,
        id -> { throw new AssertionError("Original proposal must remain durable"); }, clock, finalCodec, accountCodec, acceptance, creation);
    restarted.withAcknowledged(73, () -> {
      assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
      jdbc.update("UPDATE account_invite SET active_recipient_key=NULL,tenant_id_reservation_token=NULL WHERE id=73");
      return null;
    });
    assertEquals(firstAck, jdbc.queryForObject("SELECT response_json FROM protected_invitation_cancellation WHERE invite_id=73", String.class));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM protected_account_recovery", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM admin", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM account_inactivity", Integer.class));
    assertNull(jdbc.queryForObject("SELECT active_recipient_key FROM account_invite WHERE id=73", String.class));
    Files.writeString(Path.of(required("PAIR_WITNESS")), codec.canonical(Map.of(
        "ownerSqlAndNativePairComplete", true, "lostCreateReplyRecoveredFromPreparedOnly", true,
        "lostCompensateReplyReconciled", true, "independentNativeFinalStatusBeforeRestartRelease", true,
        "originalRestrictionAckImmutable", true, "realCheckedCreatorAndAdminBroker", true)));
  }

  private static String nativePhase(String path) {
    if (path.contains("/openid-connect/token")) return "token";
    if (path.startsWith("/admin/")) return "stock-denial";
    if (path.endsWith("/accounts")) return "create";
    if (path.endsWith("/recover")) return "recover";
    if (path.endsWith("/compensate")) return "compensate";
    if (path.endsWith("/cleanup-status")) return "cleanup-status";
    if (path.endsWith("/cancel")) return "cancel";
    if (path.endsWith("/status")) return "status";
    throw new AssertionError("Unexpected cleanup pair HTTP route");
  }
}
