package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.IdentityManager;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakCustomConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfAppOtpMaintenance;
import de.caritas.cob.userservice.api.adapters.web.dto.OneTimePasswordDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.accountinvite.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/** Selected explicitly only by the isolated native workflow; missing fixture is a failure. */
class UserSelfAppOtpNativePairProof {
  private static final List<Map<String, Object>> PHASES = new ArrayList<>();

  private static void phase(String name, int status) throws java.io.IOException {
    if (!Set.of(
                "owner-ready",
                "human-signed",
                "machine-signed",
                "stock-denied",
                "public-first-call",
                "public-after-first-call",
                "public-retry",
                "public-success",
                "native-read-http",
                "native-app-http",
                "native-email-http",
                "native-token-http",
                "native-stock-http")
            .contains(name)
        || status < 0
        || status > 599
        || PHASES.size() >= 100) throw new IllegalArgumentException("Invalid fixture observation");
    PHASES.add(Map.of("phase", name, "status", status));
    java.nio.file.Files.writeString(
        java.nio.file.Path.of(required("PAIR_WITNESS")),
        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(PHASES));
  }

  private static String required(String name) {
    String value = System.getenv(name);
    assertTrue(
        value != null && !value.isBlank(), "Required isolated native fixture field is absent");
    return value;
  }

  @Test
  void actualPublicUserAppLostNativeReplyReplaysOneCredentialAndCommitsOwnedInvite()
      throws Exception {
    runPair(false);
  }

  @Test
  void actualPublicUserEmailLostSentReplyPreservesEarlierOriginalMachineExpiry()
      throws Exception {
    runPair(true);
  }

  private void runPair(boolean email) throws Exception {
    assertEquals(email ? "email" : "app", required("PAIR_KIND"));
    PHASES.clear();
    assertEquals("true", required("GITHUB_ACTIONS"));
    assertEquals("Linux", System.getProperty("os.name"));
    String base = required("PAIR_NATIVE_BASE");
    assertTrue(base.matches("http://127\\.0\\.0\\.1:[0-9]+"));
    String realm = required("PAIR_REALM");
    String issuer = "http://maintenance-human-fixture.invalid/realms/" + realm;
    String account = required("PAIR_ACCOUNT_ID");
    String username = required("PAIR_USERNAME");
    String realmId = required("PAIR_REALM_ID");
    String operation = UUID.randomUUID().toString();
    var key = Base64.getDecoder().decode(required("PAIR_CONTEXT_KEY_B64"));
    assertEquals(32, key.length);
    var codec = new AccountLifecycleCodec(key);
    var http = new RestTemplate();
    http.setRequestFactory(new org.springframework.http.client.BufferingClientHttpRequestFactory(http.getRequestFactory()));
    var decoderReference = new AtomicReference<JwtDecoder>();
    var inPublicCall = new java.util.concurrent.atomic.AtomicBoolean();
    var firstMachineExpiry = new java.util.concurrent.atomic.AtomicLong();
    var humanExpiry = new java.util.concurrent.atomic.AtomicLong();
    String pendingEmail = "pair-pending@oriso.org";
    var nativeCalls = new AtomicInteger();
    var activationCalls = new AtomicInteger();
    var lost = new AtomicInteger();
    var frozenWire = new AtomicReference<String>();
    var frozenCipher = new AtomicReference<String>();
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:app-native-"
                + UUID.randomUUID()
                + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    var jdbc = new JdbcTemplate(source);
    var transactions = new DataSourceTransactionManager(source);
    try {
      jdbc.execute(
          "create table user(user_id varchar(36) primary key,tenant_id bigint,delete_date timestamp,username varchar(512),email varchar(320))");
      jdbc.update("insert into user values(?,?,null,?,'original@oriso.org')", account, 11L, username);
      jdbc.execute(
          "create table account_invite(id bigint primary key,accepted_by_user_id varchar(36),two_factor_status varchar(32),write_count int)");
      jdbc.update("insert into account_invite values(1,?,'PENDING_SETUP',0)", account);
      new ResourceDatabasePopulator(
              new ClassPathResource(
                  "db/changelog/changeset/20261022_identity_self_app_otp_367/migrate.sql"))
          .execute(source);
      new ResourceDatabasePopulator(new ClassPathResource(
          "db/changelog/changeset/20261022_identity_self_otp_email_367/migrate.sql")).execute(source);
      http.getInterceptors()
          .add(
              (outbound, bytes, execution) -> {
                URI logical = outbound.getURI();
                assertEquals("maintenance-human-fixture.invalid", logical.getHost());
                var physical =
                    URI.create(
                        base
                            + logical.getRawPath()
                            + (logical.getRawQuery() == null ? "" : "?" + logical.getRawQuery()));
                var mapped =
                    new HttpRequestWrapper(outbound) {
                      @Override
                      public URI getURI() {
                        return physical;
                      }
                    };
                boolean mutation = logical.getPath().contains(email
                    ? "/otp-config/send-verification-mail/" : "/otp-config/setup-otp/");
                if (mutation) {
                  assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                  assertEquals(
                      "FROZEN",
                      jdbc.queryForObject(
                          "select status from identity_self_app_otp_intent where operation_id=?",
                          String.class,
                          operation));
                  assertEquals(
                      "PENDING_SETUP",
                      jdbc.queryForObject(
                          "select two_factor_status from account_invite where id=1", String.class));
                  String wire = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                  if (email) {
                    assertTrue(firstMachineExpiry.get() > 0);
                    assertTrue(firstMachineExpiry.get() < humanExpiry.get());
                    assertEquals(firstMachineExpiry.get(), codec.read(wire, JsonNode.class)
                        .path("authorityValidUntil").longValue());
                  }
                  String cipher =
                      jdbc.queryForObject(
                          "select envelope from identity_self_app_otp_intent where operation_id=?",
                          String.class,
                          operation);
                  if (frozenWire.get() == null) {
                    frozenWire.set(wire);
                    frozenCipher.set(cipher);
                  } else {
                    assertTrue(
                        frozenWire.get().equals(wire),
                        "Actual exact native body must remain frozen");
                    assertTrue(
                        frozenCipher.get().equals(cipher),
                        "Actual exact ciphertext must remain frozen");
                  }
                  nativeCalls.incrementAndGet();
                }
                var response = execution.execute(mapped, bytes);
                if (email && inPublicCall.get() && logical.getPath().endsWith("/token")) {
                  // Read only in memory; the same native signature decoder independently validates bounds.
                  var nativeToken = codec.read(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
                  var verified = decoderReference.get().decode(nativeToken.path("access_token").textValue());
                  firstMachineExpiry.compareAndSet(0, verified.getExpiresAt().getEpochSecond());
                }
                phase(
                    mutation
                        ? email ? "native-email-http" : "native-app-http"
                        : logical.getPath().endsWith("/read")
                            ? "native-read-http"
                            : logical.getPath().endsWith("/token")
                                ? "native-token-http"
                                : "native-stock-http",
                    response.getStatusCode().value());
                if (mutation && (!email || response.getStatusCode().value() == 200)
                    && lost.getAndIncrement() == 0) {
                  assertEquals(email ? 200 : 201, response.getStatusCode().value());
                  response.getBody().readAllBytes();
                  response.close();
                  throw new ResourceAccessException("Fixture lost actual committed native reply");
                }
                return response;
              });
      phase("owner-ready", 0);
      var form = new LinkedMultiValueMap<String, String>();
      form.add("grant_type", "password");
      form.add("client_id", required("PAIR_HUMAN_CLIENT"));
      form.add("username", username);
      form.add("password", required("PAIR_PASSWORD"));
      form.add("scope", "openid");
      var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
      var tokens =
          http.postForObject(
              issuer + "/protocol/openid-connect/token",
              new HttpEntity<>(form, headers),
              Map.class);
      assertNotNull(tokens);
      assertTrue(tokens.get("access_token") instanceof String);
      String humanBearer = (String) tokens.get("access_token");
      var decoder =
          NimbusJwtDecoder.withJwkSetUri(
                  base + "/realms/" + realm + "/protocol/openid-connect/certs")
              .build();
      decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
      decoderReference.set(decoder);
      var jwt = decoder.decode(humanBearer);
      assertEquals(account, jwt.getSubject());
      assertEquals(required("PAIR_HUMAN_CLIENT"), jwt.getClaimAsString("azp"));
      assertEquals(Set.of("userservice"), Set.copyOf(jwt.getAudience()));
      assertEquals(
          Set.of("user"),
          Set.copyOf((List<?>) ((Map<?, ?>) jwt.getClaims().get("realm_access")).get("roles")));
      assertEquals("11", jwt.getClaimAsString("tenantId"));
      humanExpiry.set(jwt.getExpiresAt().getEpochSecond());
      phase("human-signed", 0);
      var identity = new IdentityConfig();
      identity.setOpenidConnectUrl(issuer + "/protocol/openid-connect");
      identity.setOtpAllowedForUsers(true);
      var environment = new MockEnvironment().withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS");
      for (var actor : TaskActor.values()) {
        String prefix = "IDENTITY_" + actor.name();
        environment.setProperty(
            prefix + "_CLIENT_ID", required("PAIR_" + actor.name() + "_CLIENT"));
        environment.setProperty(
            prefix + "_CLIENT_SECRET", required("PAIR_" + actor.name() + "_SECRET"));
        environment.setProperty(
            prefix + "_SERVICE_SUBJECT", required("PAIR_" + actor.name() + "_SUBJECT"));
      }
      var actors = new TaskActorConfiguration(environment, new KeycloakCustomConfig(), identity);
      var config = new KeycloakConfig();
      config.setAuthServerUrl("http://maintenance-human-fixture.invalid");
      config.setRealm(realm);
      var human =
          new AuthenticatedUser(
              account, username, Set.of("user"), humanBearer, Set.of("ROLE_USER"));
      human.setTenantId(11L);
      var broker =
          new TaskActorTokenBroker(
              actors, new KeycloakAuthClient(http, human, identity), decoder, config);
      var checkedMachine = broker.issueCheckedMaintenance(TaskPurpose.OTP);
      assertEquals(actors.require(TaskPurpose.OTP).serviceSubject(), checkedMachine.subject());
      var machineJwt = decoder.decode(checkedMachine.accessToken());
      if (email) {
        assertTrue(machineJwt.getExpiresAt().isBefore(jwt.getExpiresAt()));
        assertTrue(machineJwt.getExpiresAt().getEpochSecond() <= Instant.now().getEpochSecond() + 100);
      }
      phase("machine-signed", 0);
      assertEquals(
          Set.of("userservice", "oriso-account-lifecycle", "oriso-otp"),
          Set.copyOf(machineJwt.getAudience()));
      try {
        http.exchange(
            "http://maintenance-human-fixture.invalid/admin/realms/" + realm + "/users/" + account,
            HttpMethod.GET,
            new HttpEntity<>(bearer(checkedMachine.accessToken())),
            String.class);
        fail("Canonical ADMIN must have no stock native target query power");
      } catch (org.springframework.web.client.HttpClientErrorException denied) {
        assertEquals(403, denied.getStatusCode().value());
      }
      phase("stock-denied", 403);
      var request = new MockHttpServletRequest("PUT", email ? "/service/users/2fa/email" : "/service/users/2fa/app");
      var authentication =
          new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")));
      request.setUserPrincipal(authentication);
      request.addHeader("Authorization", "Bearer " + humanBearer);
      request.addHeader("Idempotency-Key", operation);
      SecurityContextHolder.getContext().setAuthentication(authentication);
      var invites = mock(AccountInviteRepository.class);
      when(invites.findAllByAcceptedByUserIdAndTwoFactorStatus(eq(account), any()))
          .thenAnswer(
              invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                TwoFactorGateStatus wanted = invocation.getArgument(1);
                String actual =
                    jdbc.queryForObject(
                        "select two_factor_status from account_invite where id=1", String.class);
                if (!wanted.name().equals(actual)) return List.of();
                var invite = new AccountInvite();
                invite.setId(1L);
                invite.setAcceptedByUserId(account);
                invite.setTwoFactorStatus(wanted);
                return List.of(invite);
              });
      when(invites.saveAll(any()))
          .thenAnswer(
              invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                for (AccountInvite invite : (Iterable<AccountInvite>) invocation.getArgument(0))
                  jdbc.update(
                      "update account_invite set two_factor_status=?,write_count=write_count+1 where id=1",
                      invite.getTwoFactorStatus().name());
                activationCalls.incrementAndGet();
                return invocation.getArgument(0);
              });
      var inviteService =
          new AccountInviteService(
              invites,
              mock(InviteEmailTemplateRepository.class),
              human,
              mock(IdentityEmailOwnerLookup.class),
              transactions,
              mock(AccountInviteAccessPolicy.class),
              mock(AgencyFacts.class),
              mock(InviteTargetResolver.class),
              mock(ReservationLedger.class),
              mock(UnitQueue.class),
              mock(InviteDelivery.class),
              mock(ExistingAccountSetupIssuer.class));
      var mode = mock(IdentityClientConfig.class);
      when(mode.isFiveActorMode()).thenReturn(true);
      var legacy = mock(IdentityManager.class);
      var delegate =
          new UserTwoFactorAuthControllerDelegate(
              human,
              identity,
              legacy,
              mock(AccountManaging.class),
              inviteService,
              new UserDtoMapper(identity),
              new UsernameTranscoder());
      var controller = mock(UserController.class, CALLS_REAL_METHODS);
      ReflectionTestUtils.setField(controller, "userTwoFactorAuthControllerDelegate", delegate);
      var dto = new OneTimePasswordDTO();
      dto.setSecret("JBSWY3DPEHPK3PXP");
      dto.setOtp(currentCode());
      delegate.setSelfAppOtpMaintenance(
          new SelfAppOtpMaintenance(
              mode,
              human,
              request,
              jdbc,
              transactions,
              actors,
              broker,
              codec,
              http,
              Clock.systemUTC(),
              "http://maintenance-human-fixture.invalid",
              realm,
              realmId));
      var emailDto = new de.caritas.cob.userservice.api.adapters.web.dto.EmailDTO();
      emailDto.setEmail(pendingEmail);
      inPublicCall.set(true);
      phase("public-first-call", 0);
      assertThrows(RuntimeException.class, () -> {
        if (email) controller.startTwoFactorAuthByEmailSetup(emailDto);
        else controller.activateTwoFactorAuthByApp(dto);
      });
      phase("public-after-first-call", 0);
      assertEquals(
          "FROZEN",
          jdbc.queryForObject(
              "select status from identity_self_app_otp_intent where operation_id=?",
              String.class,
              operation));
      assertEquals(0, activationCalls.get());
      // Reconstruct the actual public delegate's owner over the same durable rows; no cached grant.
      delegate.setSelfAppOtpMaintenance(
          new SelfAppOtpMaintenance(
              mode,
              human,
              request,
              jdbc,
              transactions,
              actors,
              broker,
              codec,
              http,
              Clock.systemUTC(),
              "http://maintenance-human-fixture.invalid",
              realm,
              realmId));
      phase("public-retry", 0);
      for (int retry = 0; retry < 2; retry++) {
        var resolved = assertDoesNotThrow(() -> email
            ? controller.startTwoFactorAuthByEmailSetup(emailDto)
            : controller.activateTwoFactorAuthByApp(dto));
        assertEquals(email ? 204 : 200, resolved.getStatusCode().value());
      }
      assertEquals(
          "ACKNOWLEDGED",
          jdbc.queryForObject(
              "select status from identity_self_app_otp_intent where operation_id=?",
              String.class,
              operation));
      assertEquals(
          email ? "PENDING_SETUP" : "ACTIVE",
          jdbc.queryForObject(
              "select two_factor_status from account_invite where id=1", String.class));
      assertEquals(
          email ? 0 : 1,
          jdbc.queryForObject("select write_count from account_invite where id=1", Integer.class));
      assertEquals(email ? 0 : 1, activationCalls.get());
      assertEquals(email ? 3 : 2, nativeCalls.get());
      if (email) {
        assertEquals(operation, jdbc.queryForObject(
            "select start_operation_id from identity_self_otp_pending_email where account_id=?", String.class, account));
        assertEquals("original@oriso.org", jdbc.queryForObject("select email from user where user_id=?", String.class, account));
      }
      String receipt =
          jdbc.queryForObject(
              "select receipt from identity_self_app_otp_intent where operation_id=?",
              String.class,
              operation);
      assertNotNull(receipt);
      var facts =
          codec.read(
              new String(
                  Base64.getUrlDecoder().decode(receipt.split("\\.")[0]),
                  java.nio.charset.StandardCharsets.UTF_8),
              JsonNode.class);
      assertEquals("backend-account-admin", facts.path("ownerClient").textValue());
      if (email) {
        assertEquals("otp.email-start", facts.path("operation").textValue());
        assertEquals("SENT", facts.path("deliveryState").textValue());
        assertEquals(firstMachineExpiry.get(), facts.path("authorityValidUntil").longValue());
        assertEquals(firstMachineExpiry.get(), jdbc.queryForObject(
            "select authority_valid_until from identity_self_app_otp_intent where operation_id=?", Long.class, operation));
      }
      assertEquals(
          actors.require(TaskPurpose.OTP).serviceSubject(), facts.path("ownerSubject").textValue());
      var stored =
          jdbc.queryForMap(
              "select * from identity_self_app_otp_intent where operation_id=?", operation);
      String flat = stored.toString();
      for (String secret :
          List.of(humanBearer, checkedMachine.accessToken(), dto.getSecret(), dto.getOtp()))
        assertFalse(
            flat.contains(secret), "No plaintext secret or JWT may enter the private durable row");
      if (email) assertFalse(flat.contains(pendingEmail));
      phase("public-success", email ? 204 : 200);
      verifyNoInteractions(legacy);
    } finally {
      SecurityContextHolder.clearContext();
      jdbc.execute("shutdown");
    }
  }

  private static HttpHeaders bearer(String token) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return headers;
  }

  private static String currentCode() throws Exception {
    var mac = Mac.getInstance("HmacSHA1");
    mac.init(new SecretKeySpec("JBSWY3DPEHPK3PXP".getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
    byte[] value =
        mac.doFinal(ByteBuffer.allocate(8).putLong(Instant.now().getEpochSecond() / 30).array());
    int at = value[value.length - 1] & 15;
    int number =
        ((value[at] & 127) << 24)
            | ((value[at + 1] & 255) << 16)
            | ((value[at + 2] & 255) << 8)
            | (value[at + 3] & 255);
    return String.format(java.util.Locale.ROOT, "%06d", number % 1000000);
  }
}
