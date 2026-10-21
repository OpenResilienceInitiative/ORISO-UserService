package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.IdentityManager;
import de.caritas.cob.userservice.api.adapters.keycloak.*;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfAppOtpMaintenance;
import de.caritas.cob.userservice.api.adapters.web.dto.OneTimePasswordDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.config.auth.SelfAppSignedIssuer;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.OtpInfoDTO;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.in.IdentityPolicy;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.accountinvite.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Actual public controller/delegate/invite code and H2 ownership; native JWT pairing is separate.
 */
class UserSelfAppOtpScenarioCBoundaryTest {
  private static final String ACCOUNT = "11111111-1111-4111-8111-111111111111";
  private static final String REALM_ID = "22222222-2222-4222-8222-222222222222";
  private static final String ADMIN = "33333333-3333-4333-8333-333333333333";
  private static final String SECRET = "JBSWY3DPEHPK3PXP";
  private static final String CODE = "123456";
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

  @AfterEach
  void releaseThreadSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void publicAppSetupFreezesEncryptedOwnedIntentAndMarksInviteOnlyAfterVerifiedReceipt()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.expectNative(false, false);
      assertDoesNotThrow(
          () ->
              assertEquals(
                  200,
                  fixture
                      .controller
                      .activateTwoFactorAuthByApp(fixture.dto())
                      .getStatusCode()
                      .value()));
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      assertEquals(1, fixture.nativeExecutions.get());
      fixture.assertProtectedStorage();
      fixture.server.verify();
    }
  }

  @Test
  void publicAppSetupLostReplyNewOwnerReplaysExactFrozenBytesWithoutSecondActivation()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.expectNative(true, false);
      assertThrows(
          RuntimeException.class,
          () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      assertEquals("FROZEN", fixture.status());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      String encrypted = fixture.envelope();
      fixture.jdbc.update("update user set username='later-domain-name' where user_id=?", ACCOUNT);
      fixture.installOwner();
      assertDoesNotThrow(
          () ->
              assertEquals(
                  200,
                  fixture
                      .controller
                      .activateTwoFactorAuthByApp(fixture.dto())
                      .getStatusCode()
                      .value()));
      assertDoesNotThrow(() -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      assertTrue(encrypted.equals(fixture.envelope()), "An exact retry cannot reseal the intent");
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals(1, fixture.nativeExecutions.get());
      assertEquals(1, fixture.domainWrites());
      fixture.server.verify();
    }
  }

  @Test
  void publicAppSetupSameIdChangedCompleteSecretRequestRefusesBeforeAnotherRpc() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.expectNative(false, false);
      assertDoesNotThrow(() -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      var changed = fixture.dto();
      changed.setSecret("KRUGS4ZANFZSAYJA");
      assertThrows(
          ConflictException.class, () -> fixture.controller.activateTwoFactorAuthByApp(changed));
      assertEquals("ACKNOWLEDGED", fixture.status());
      assertEquals(1, fixture.nativeExecutions.get());
      assertEquals(1, fixture.domainWrites());
      fixture.server.verify();
    }
  }

  @Test
  void publicAppSetupInvalidNativeCodeCommitsTerminalRefusalBeforeReturningPublic400()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.expectNative(false, true);
      assertDoesNotThrow(
          () ->
              assertEquals(
                  400,
                  fixture
                      .controller
                      .activateTwoFactorAuthByApp(fixture.dto())
                      .getStatusCode()
                      .value()));
      assertEquals("TERMINAL_REFUSAL", fixture.status());
      assertEquals(
          401,
          fixture.jdbc.queryForObject(
              "select native_status from identity_self_app_otp_intent where operation_id=?",
              Integer.class,
              fixture.operation));
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(0, fixture.domainWrites());
      fixture.installOwner();
      assertEquals(
          400,
          fixture.controller.activateTwoFactorAuthByApp(fixture.dto()).getStatusCode().value());
      assertEquals(1, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void publicAppSetupInvalidMacKeepsFrozenOwnershipAndCannotActivateInvite() throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.tamperReceipt = true;
      fixture.expectNative(false, false);
      assertThrows(
          ServiceUnavailableException.class,
          () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      assertEquals("FROZEN", fixture.status());
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(0, fixture.domainWrites());
      assertEquals(1, fixture.nativeExecutions.get());
      assertNull(
          fixture.jdbc.queryForObject(
              "select receipt from identity_self_app_otp_intent where operation_id=?",
              String.class,
              fixture.operation));
      fixture.server.verify();
    }
  }

  @Test
  void publicAppSetupMissingIntentAndForeignCurrentDomainRefuseBeforePrivateRowOrRpc()
      throws Exception {
    try (var fixture = new Fixture(true, true)) {
      fixture.request.removeHeader("Idempotency-Key");
      assertThrows(
          BadRequestException.class,
          () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      fixture.request.addHeader("Idempotency-Key", fixture.operation);
      fixture.jdbc.update("update user set tenant_id=8 where user_id=?", ACCOUNT);
      assertThrows(
          ForbiddenException.class,
          () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      assertEquals(
          0,
          fixture.jdbc.queryForObject(
              "select count(*) from identity_self_app_otp_intent", Integer.class));
      assertEquals(0, fixture.domainWrites());
      assertEquals(0, fixture.nativeExecutions.get());
      fixture.server.verify();
    }
  }

  @Test
  void missingScopedServiceKeepsActualCGuardAndLegacyAppSetupStillWorks() throws Exception {
    try (var fixture = new Fixture(true, false)) {
      assertThrows(
          ScopedIdentityCommandRequiredException.class,
          () -> fixture.controller.activateTwoFactorAuthByApp(fixture.dto()));
      verifyNoInteractions(fixture.nativeClient);
      assertEquals("PENDING_SETUP", fixture.inviteStatus());
      assertEquals(0, fixture.domainWrites());
    }
    try (var fixture = new Fixture(false, false)) {
      fixture.request.removeHeader("Idempotency-Key");
      assertEquals(
          200,
          fixture.controller.activateTwoFactorAuthByApp(fixture.dto()).getStatusCode().value());
      assertEquals("ACTIVE", fixture.inviteStatus());
      assertEquals(1, fixture.domainWrites());
      assertEquals(
          0,
          fixture.jdbc.queryForObject(
              "select count(*) from identity_self_app_otp_intent", Integer.class));
      verify(fixture.nativeClient).putForEntity(any(), any(), any(), eq(OtpInfoDTO.class));
    }
  }

  private static final class Fixture implements AutoCloseable {
    final boolean fiveActors;
    final SelfAppSignedIssuer issuer = new SelfAppSignedIssuer(ADMIN);
    final AuthenticatedUser human =
        new AuthenticatedUser(
            ACCOUNT, "ordinary", Set.of("user"), "fixture-human", Set.of("ROLE_USER"));
    final MockHttpServletRequest request =
        new MockHttpServletRequest("PUT", "/service/users/2fa/app");
    final DriverManagerDataSource source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:self-app-"
                + UUID.randomUUID()
                + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    final JdbcTemplate jdbc = new JdbcTemplate(source);
    final DataSourceTransactionManager transactions = new DataSourceTransactionManager(source);
    final IdentityClientConfig mode = mock(IdentityClientConfig.class);
    final AccountLifecycleCodec codec = new AccountLifecycleCodec(KEY);
    final RestTemplate http = new RestTemplate();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    final KeycloakClient nativeClient = mock(KeycloakClient.class);
    final UserController controller = mock(UserController.class, CALLS_REAL_METHODS);
    final UserTwoFactorAuthControllerDelegate delegate;
    final String operation = UUID.randomUUID().toString();
    final AtomicInteger nativeExecutions = new AtomicInteger();
    boolean tamperReceipt;

    Fixture(boolean fiveActors, boolean scopedService) throws Exception {
      this.fiveActors = fiveActors;
      try {
        when(mode.isFiveActorMode()).thenReturn(fiveActors);
        when(mode.getOtpUrl(anyString(), anyString())).thenReturn(issuer.base() + "/legacy-otp");
        when(nativeClient.putForEntity(any(), any(), any(), eq(OtpInfoDTO.class)))
            .thenReturn(ResponseEntity.status(HttpStatus.CREATED).body(new OtpInfoDTO()));
        human.setTenantId(7L);
        var jwt =
            Jwt.withTokenValue("fixture-human")
                .header("alg", "RS256")
                .subject(ACCOUNT)
                .issuer(issuer.issuer())
                .claim("azp", "app")
                .claim("tenantId", "7")
                .issuedAt(Instant.now().minusSeconds(10))
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        var authentication =
            new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        request.setUserPrincipal(authentication);
        request.addHeader("Authorization", "Bearer fixture-human");
        request.addHeader("Idempotency-Key", operation);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        jdbc.execute(
            "create table user(user_id varchar(36) primary key,tenant_id bigint,delete_date timestamp,username varchar(512))");
        jdbc.update("insert into user values(?,?,null,'ordinary')", ACCOUNT, 7L);
        jdbc.execute(
            "create table account_invite(id bigint primary key,accepted_by_user_id varchar(36),two_factor_status varchar(32),write_count int)");
        jdbc.update("insert into account_invite values(1,?,'PENDING_SETUP',0)", ACCOUNT);
        jdbc.execute(
            "create table identity_self_app_otp_intent(operation_id varchar(36) primary key,realm_id varchar(512),realm_name varchar(512),account_id varchar(36),tenant_id bigint,owner_client varchar(512),owner_subject varchar(36),request_digest varchar(64),target_json clob,status varchar(32),authority_valid_until bigint,metadata_json clob,envelope clob,pending_marker boolean,receipt clob,native_status int,updated_at timestamp)");
        var invites = mock(AccountInviteRepository.class);
        when(invites.findAllByAcceptedByUserIdAndTwoFactorStatus(eq(ACCOUNT), any()))
            .thenAnswer(
                invocation -> {
                  if (fiveActors)
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                  TwoFactorGateStatus wanted = invocation.getArgument(1);
                  if (!wanted.name().equals(inviteStatus())) return List.of();
                  var invite = new AccountInvite();
                  invite.setId(1L);
                  invite.setAcceptedByUserId(ACCOUNT);
                  invite.setTwoFactorStatus(wanted);
                  return List.of(invite);
                });
        when(invites.saveAll(any()))
            .thenAnswer(
                invocation -> {
                  if (fiveActors)
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                  Iterable<AccountInvite> saved = invocation.getArgument(0);
                  for (var invite : saved)
                    jdbc.update(
                        "update account_invite set two_factor_status=?,write_count=write_count+1 where id=?",
                        invite.getTwoFactorStatus().name(),
                        invite.getId());
                  return saved;
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
        var nativeService =
            new KeycloakService(
                human,
                mock(UserAccountInputValidator.class),
                mode,
                nativeClient,
                new KeycloakMapper(),
                mock(UserHelper.class),
                mock(KeycloakAuthClient.class));
        var transcoder = new UsernameTranscoder();
        var identity =
            new IdentityManager(
                nativeService,
                nativeService,
                nativeService,
                nativeService,
                nativeService,
                nativeService,
                nativeService,
                transcoder);
        var policy = mock(IdentityPolicy.class);
        when(policy.isTwoFactorAuthenticationAllowed(anySet())).thenReturn(true);
        delegate =
            new UserTwoFactorAuthControllerDelegate(
                human,
                policy,
                identity,
                mock(AccountManaging.class),
                inviteService,
                new UserDtoMapper(policy),
                transcoder);
        ReflectionTestUtils.setField(controller, "userTwoFactorAuthControllerDelegate", delegate);
        var checked = issuer.broker().issueCheckedMaintenance(TaskPurpose.OTP);
        assertEquals(ADMIN, checked.subject());
        assertTrue(checked.validatedJwtExpiresAt() > Instant.now().getEpochSecond());
        assertEquals(
            1,
            jdbc.queryForObject(
                "select count(*) from user where user_id=? and tenant_id=7 and delete_date is null",
                Integer.class,
                ACCOUNT));
        assertEquals("PENDING_SETUP", inviteStatus());
        if (scopedService) installOwner();
      } catch (Exception | AssertionError failure) {
        issuer.close();
        jdbc.execute("SHUTDOWN");
        throw failure;
      }
    }

    void installOwner() {
      delegate.setSelfAppOtpMaintenance(
          new SelfAppOtpMaintenance(
              mode,
              human,
              request,
              jdbc,
              transactions,
              issuer.actors(),
              issuer.broker(),
              codec,
              http,
              Clock.systemUTC(),
              issuer.base(),
              "synthetic",
              REALM_ID));
    }

    OneTimePasswordDTO dto() {
      var dto = new OneTimePasswordDTO();
      dto.setOtp(CODE);
      dto.setSecret(SECRET);
      return dto;
    }

    String status() {
      var rows =
          jdbc.queryForList(
              "select status from identity_self_app_otp_intent where operation_id=?",
              String.class,
              operation);
      return rows.isEmpty() ? null : rows.getFirst();
    }

    String envelope() {
      return jdbc.queryForObject(
          "select envelope from identity_self_app_otp_intent where operation_id=?",
          String.class,
          operation);
    }

    String inviteStatus() {
      return jdbc.queryForObject(
          "select two_factor_status from account_invite where id=1", String.class);
    }

    int domainWrites() {
      return jdbc.queryForObject(
          "select write_count from account_invite where id=1", Integer.class);
    }

    void assertProtectedStorage() {
      String values =
          jdbc.queryForList(
                  "select * from identity_self_app_otp_intent where operation_id=?", operation)
              .toString();
      for (var value : List.of(SECRET, CODE, "fixture-human", "ordinary"))
        assertFalse(
            values.contains(value),
            "Private intent must contain no plaintext credentials or bearer");
      assertNotNull(envelope());
    }

    void expectNative(boolean lostReply, boolean refusal) {
      server
          .expect(
              requestTo(
                  issuer.issuer() + "/oriso-account-maintenance/v1/accounts/" + ACCOUNT + "/read"))
          .andExpect(method(HttpMethod.POST))
          .andRespond(
              outbound -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("PREPARED", status());
                assertEquals("PENDING_SETUP", inviteStatus());
                assertNull(envelope());
                var read =
                    codec.read(
                        ((MockClientHttpRequest) outbound).getBodyAsString(), JsonNode.class);
                checkProof(
                    outbound.getHeaders(),
                    read,
                    "account.read",
                    "ACCOUNT_MAINTENANCE",
                    "oriso-account-maintenance",
                    "POST");
                return withSuccess(
                        "{\"id\":\""
                            + ACCOUNT
                            + "\",\"username\":\"ordinary\",\"email\":null,\"firstName\":null,\"lastName\":null,\"enabled\":true,\"emailVerified\":false,\"tenantId\":\"7\",\"preferredLanguage\":\"en\",\"effectiveRoles\":[\"user\"],\"passwordChangeRequired\":false,\"state\":\""
                            + "a".repeat(64)
                            + "\"}",
                        MediaType.APPLICATION_JSON)
                    .createResponse(outbound);
              });
      var frozen = new AtomicReference<String>();
      var encrypted = new AtomicReference<String>();
      var response = new AtomicReference<String>();
      server
          .expect(
              lostReply ? ExpectedCount.twice() : ExpectedCount.once(),
              requestTo(issuer.issuer() + "/otp-config/setup-otp/ordinary"))
          .andExpect(method(HttpMethod.PUT))
          .andRespond(
              outbound -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("FROZEN", status());
                assertEquals("PENDING_SETUP", inviteStatus());
                assertEquals(0, domainWrites());
                assertEquals(
                    Boolean.TRUE,
                    jdbc.queryForObject(
                        "select pending_marker from identity_self_app_otp_intent where operation_id=?",
                        Boolean.class,
                        operation));
                assertProtectedStorage();
                String wire = ((MockClientHttpRequest) outbound).getBodyAsString();
                var body = codec.read(wire, ObjectNode.class);
                var materialized = body.deepCopy();
                materialized.put("username", "ordinary");
                checkProof(
                    outbound.getHeaders(),
                    materialized,
                    "otp.app-setup",
                    "OTP",
                    "oriso-otp",
                    "PUT");
                if (frozen.get() == null) {
                  frozen.set(wire);
                  encrypted.set(envelope());
                  assertTrue(CODE.equals(body.path("otp").path("initialCode").textValue()));
                  assertTrue(SECRET.equals(body.path("otp").path("secret").textValue()));
                  assertEquals(operation, body.path("operationId").textValue());
                  assertEquals("a".repeat(64), body.path("expectedState").textValue());
                  int status = refusal ? 401 : 201;
                  JsonNode nativeResult =
                      codec.read(
                          refusal
                              ? "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid otp code\"}"
                              : "{\"info\":\"OTP credential created\"}",
                          JsonNode.class);
                  var outcome = codec.read("{}", ObjectNode.class);
                  outcome.put("realmId", REALM_ID);
                  outcome.put("accountId", ACCOUNT);
                  outcome.put("operationId", operation);
                  outcome.put("operation", "otp.app-setup");
                  outcome.put("ownerClient", "backend-account-admin");
                  outcome.put("ownerSubject", ADMIN);
                  outcome.put("intentDigest", codec.digest("otp-intent", materialized));
                  outcome.put("previousState", "a".repeat(64));
                  outcome.put("tenantId", "7");
                  outcome.put(
                      "originDigest",
                      codec.digest("otp-origin", body.path("target").path("origin")));
                  outcome.put("status", status);
                  outcome.put("resultDigest", codec.digest("otp-result", nativeResult));
                  String encoded =
                      Base64.getUrlEncoder()
                          .withoutPadding()
                          .encodeToString(
                              codec.canonical(outcome).getBytes(StandardCharsets.UTF_8));
                  var result = codec.read("{}", ObjectNode.class);
                  result.put("operationId", operation);
                  result.put("accountId", ACCOUNT);
                  result.put("status", status);
                  result.set("result", nativeResult);
                  result.put(
                      "receipt",
                      encoded
                          + "."
                          + HexFormat.of().formatHex(mac("otp-operation-receipt\n" + encoded)));
                  if (tamperReceipt)
                    result.put("receipt", result.path("receipt").textValue() + "x");
                  response.set(codec.canonical(result));
                  nativeExecutions.incrementAndGet();
                  if (lostReply)
                    throw new ResourceAccessException("Fixture committed reply was lost");
                } else {
                  assertTrue(frozen.get().equals(wire), "Exact frozen body required");
                  assertTrue(encrypted.get().equals(envelope()), "Exact frozen envelope required");
                }
                return withStatus(refusal ? HttpStatus.UNAUTHORIZED : HttpStatus.CREATED)
                    .body(response.get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .createResponse(outbound);
              });
    }

    void checkProof(
        org.springframework.http.HttpHeaders headers,
        JsonNode body,
        String operationName,
        String purpose,
        String audience,
        String method) {
      assertEquals("Bearer fixture-human", headers.getFirst("X-ORISO-Human-Authorization"));
      String machine = headers.getFirst("Authorization");
      assertNotNull(machine);
      assertTrue(machine.startsWith("Bearer "));
      var token =
          NimbusJwtDecoder.withJwkSetUri(issuer.base() + "/certs")
              .build()
              .decode(machine.substring(7));
      assertEquals(ADMIN, token.getSubject());
      assertEquals(
          Set.of("userservice", "oriso-account-lifecycle", "oriso-otp"),
          Set.copyOf(token.getAudience()));
      String proof = headers.getFirst("X-ORISO-Task-Context");
      assertNotNull(proof);
      String[] parts = proof.split("\\.");
      assertEquals(2, parts.length);
      assertTrue(
          java.security.MessageDigest.isEqual(
              mac("proof\n" + parts[0]), Base64.getUrlDecoder().decode(parts[1])));
      var claims =
          codec.read(
              new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8),
              JsonNode.class);
      assertEquals(operationName, claims.path("operation").textValue());
      assertEquals(purpose, claims.path("purpose").textValue());
      assertEquals(audience, claims.path("aud").textValue());
      assertEquals(method, claims.path("method").textValue());
      assertEquals("realm/" + REALM_ID + "/account/" + ACCOUNT, claims.path("target").textValue());
      assertEquals("backend-account-admin", claims.path("taskClient").textValue());
      assertEquals(ADMIN, claims.path("taskSubject").textValue());
      assertEquals(codec.digest("payload", body), claims.path("payloadDigest").textValue());
    }

    byte[] mac(String text) {
      try {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
      } catch (java.security.GeneralSecurityException impossible) {
        throw new IllegalStateException("Fixture MAC unavailable");
      }
    }

    @Override
    public void close() {
      issuer.close();
      jdbc.execute("SHUTDOWN");
    }
  }
}
