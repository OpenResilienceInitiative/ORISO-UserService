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
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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

/** Explicit hosted-only adapter/SQL/native pair; no public coordinator or creator-issuer credit. */
class ProtectedOtpBootstrapNativePairProof {
  private static String required(String name) {
    String value = System.getenv(name);
    assertTrue(value != null && !value.isBlank(), "Required isolated native pair input absent");
    return value;
  }

  @Test
  void actualOwnedRecipientCompletesOtpAfterLostNativeReplyWithoutEnablingAccount()
      throws Exception {
    assertEquals("true", required("GITHUB_ACTIONS"));
    assertEquals("Linux", System.getProperty("os.name"));
    boolean email =
        switch (required("PAIR_KIND")) {
          case "email" -> true;
          case "app" -> false;
          default -> throw new IllegalArgumentException("Invalid pair kind");
        };
    String base = required("PAIR_NATIVE_BASE"), realm = required("PAIR_REALM");
    assertTrue(base.matches("http://127\\.0\\.0\\.1:[0-9]+"));
    String realmId = required("PAIR_REALM_ID");
    String logicalBase = "http://protected-fixture.invalid";
    String issuer = logicalBase + "/realms/" + realm;
    var codec =
        new AccountLifecycleCodec(Base64.getDecoder().decode(required("PAIR_CONTEXT_KEY_B64")));
    var requestBody = codec.read(required("PAIR_APPROVAL_REQUEST"), JsonNode.class);
    var approved = codec.read(required("PAIR_APPROVAL_RESPONSE"), JsonNode.class);
    var scope = requestBody.path("scope");
    assertEquals("73", scope.path("inviteId").asText());
    String recipient = scope.path("recipient").asText();
    var clock = Clock.systemUTC();
    var approvals =
        new ProtectedInvitationCodec(
            Base64.getDecoder().decode(required("PAIR_CONTEXT_KEY_B64")), clock, realm, realmId);
    var frozen =
        approvals.freeze(
            UUID.fromString(approved.path("approvalId").asText()),
            codec.canonical(requestBody),
            realmId,
            approved.path("approvalReceipt").path("issuerSubject").asText());
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:protected-pair-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    var jdbc = new JdbcTemplate(source);
    var manager = new DataSourceTransactionManager(source);
    jdbc.execute(
        "CREATE TABLE account_invite(id BIGINT PRIMARY KEY,target_role VARCHAR(64),tenant_id BIGINT,recipient_email VARCHAR(255),purpose VARCHAR(32),status VARCHAR(32),expires_at TIMESTAMP,token_hash VARCHAR(64),agency_id BIGINT,tenant_id_reservation_token VARCHAR(36))");
    for (String name :
        List.of(
            "20261022_protected_invitation_367",
            "20261022_protected_invitation_acceptance_367",
            "20261022_protected_otp_bootstrap_367")) {
      for (String sql :
          Files.readString(
                  Path.of("src/main/resources/db/changelog/changeset/" + name + "/migrate.sql"))
              .split(";")) if (!sql.isBlank()) jdbc.execute(sql);
    }
    for (String sql :
        Files.readString(
                Path.of(
                    "src/main/resources/db/changelog/changeset/20261022_protected_otp_bootstrap_367/operations.sql"))
            .split(";")) if (!sql.isBlank()) jdbc.execute(sql);
    jdbc.update(
        "INSERT INTO account_invite VALUES(73,?,0,?,'INVITE','EMAIL_SENT',?,?,NULL,NULL)",
        scope.path("protectedKind").asText(),
        recipient,
        Timestamp.from(Instant.parse(scope.path("validUntil").asText())),
        "c".repeat(64));
    var journal = new JdbcProtectedInvitationJournal(jdbc, manager, approvals, codec, clock);
    journal.propose(73, frozen);
    journal.recordReady(
        frozen.approvalId(), approvals.verifyAuthorized(frozen, codec.canonical(approved)));
    var registration =
        Map.of(
            "password",
            "SyntheticOnlyPassword!731",
            "firstName",
            "Protected",
            "lastName",
            "Fixture");
    var acceptance =
        new JdbcProtectedInvitationAcceptance(jdbc, manager, journal, approvals, codec, clock);
    var claim = acceptance.claim(73, "c".repeat(64), registration);
    var http = new RestTemplate();
    var lost = new AtomicInteger();
    var effectCalls = new AtomicInteger();
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
              boolean effect =
                  email
                      ? logical.getPath().contains("/send-verification-mail/")
                      : logical.getPath().contains("/setup-otp/");
              if (effect
                  && effectCalls.incrementAndGet() == (email ? 2 : 1)
                  && lost.getAndIncrement() == 0) {
                response.close();
                throw new ResourceAccessException("Synthetic lost native reply");
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
    // Explicit creator issuance double: the fixed native token is independently signature checked.
    var creator = decoder.decode(required("PAIR_CREATE_BEARER"));
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
    var creatorBroker = mock(TaskActorTokenBroker.class);
    when(creatorBroker.issue(TaskPurpose.ACCOUNT_PROVISIONING))
        .thenReturn(
            new TaskActorTokenBroker.TaskToken(
                TaskActor.ACCOUNT_CREATE, required("PAIR_CREATE_BEARER"), 60));
    var accountCodec =
        new ProtectedAccountCodec(
            Base64.getDecoder().decode(required("PAIR_CONTEXT_KEY_B64")),
            clock,
            approvals,
            realm,
            realmId,
            issuer);
    String username =
        "enc."
            + new Base32()
                .encodeAsString(recipient.getBytes(StandardCharsets.UTF_8))
                .replace('=', '.');
    var account =
        new AccountCreation(
            username, recipient, "Protected", "Fixture", "en", registration.get("password"), false);
    var bridge =
        new KeycloakProtectedAccounts(
            http, actors, creatorBroker, accountCodec, logicalBase, realm);
    var created = bridge.create(accountCodec.prepare(claim, registration, account));
    var pending = bridge.ownedRead(claim, created);
    assertEquals(username.toLowerCase(Locale.ROOT), pending.username());
    var request = new MockHttpServletRequest("POST", "/service/tenant/register");
    request.addHeader("Idempotency-Key", UUID.randomUUID().toString());
    var adapter =
        new ProtectedOtpBootstrap(
            jdbc,
            manager,
            codec,
            actors,
            broker,
            http,
            request,
            clock,
            logicalBase,
            realm,
            realmId);
    var prepared = adapter.prepare(claim, pending);
    var material =
        new TransactionTemplate(manager).execute(tx -> adapter.stagePrepared(claim, prepared));
    assertNotNull(material);
    assertFalse(material.secret().isBlank());
    ProtectedOtpBootstrap.BootstrapAttempt result;
    if (email) {
      request.setRequestURI("/service/tenant/register/two-factor/email");
      request.removeHeader("Idempotency-Key");
      request.addHeader("Idempotency-Key", UUID.randomUUID().toString());
      assertEquals(ProtectedOtpBootstrap.AttemptState.UNKNOWN, adapter.startEmail(claim).state());
      assertEquals(ProtectedOtpBootstrap.AttemptState.SENT, adapter.startEmail(claim).state());
      String code = new RestTemplate().getForObject(required("PAIR_MAIL_CALLBACK"), String.class);
      assertNotNull(code);
      assertTrue(code.matches("[0-9]{6}"));
      request.setRequestURI("/service/tenant/register/two-factor");
      request.removeHeader("Idempotency-Key");
      request.addHeader("Idempotency-Key", UUID.randomUUID().toString());
      result = adapter.finishEmail(claim, code);
      assertEquals(
          result.completion().receipt(), adapter.finishEmail(claim, code).completion().receipt());
    } else {
      request.setRequestURI("/service/tenant/register/two-factor");
      request.removeHeader("Idempotency-Key");
      request.addHeader("Idempotency-Key", UUID.randomUUID().toString());
      String code = totp(material.secret());
      assertEquals(
          ProtectedOtpBootstrap.AttemptState.UNKNOWN, adapter.setupApp(claim, code).state());
      result = adapter.setupApp(claim, code);
      assertEquals(
          result.completion().receipt(), adapter.setupApp(claim, code).completion().receipt());
    }
    assertEquals(ProtectedOtpBootstrap.AttemptState.COMPLETE, result.state());
    assertNotNull(result.completion());
    assertEquals(created.accountId(), result.completion().accountId());
    assertEquals(
        result.completion().receipt(),
        new TransactionTemplate(manager)
            .execute(tx -> adapter.completedWithinOwner(claim))
            .receipt());
    assertEquals(
        email ? 2 : 1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM protected_otp_bootstrap_operation WHERE state='ACKNOWLEDGED'",
            Integer.class));
    assertEquals(1, lost.get());
    Files.writeString(
        Path.of(required("PAIR_WITNESS")),
        codec.canonical(
            Map.of(
                "ownerSqlAndNativePairComplete",
                true,
                "oneLostNativeReplyRecovered",
                true,
                "privateCompletionVerified",
                true,
                "creatorIssuanceDouble",
                true)));
    jdbc.execute("SHUTDOWN");
  }

  private static String totp(String secret) throws Exception {
    var mac = Mac.getInstance("HmacSHA1");
    mac.init(new SecretKeySpec(new Base32().decode(secret), "HmacSHA1"));
    byte[] digest =
        mac.doFinal(ByteBuffer.allocate(8).putLong(Instant.now().getEpochSecond() / 30).array());
    int offset = digest[digest.length - 1] & 15;
    int binary = ByteBuffer.wrap(digest, offset, 4).getInt() & 0x7fffffff;
    return String.format(Locale.ROOT, "%06d", binary % 1000000);
  }
}
