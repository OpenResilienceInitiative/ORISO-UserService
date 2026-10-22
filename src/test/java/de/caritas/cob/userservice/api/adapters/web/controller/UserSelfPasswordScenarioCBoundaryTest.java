package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.SelfPasswordMaintenance;
import de.caritas.cob.userservice.api.adapters.web.dto.PasswordDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.*;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.config.VideoChatConfig;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.facade.userdata.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.in.*;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.*;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

/** Actual public controller, private SQL owner and fixed native HTTP seam; no native JWT credit. */
class UserSelfPasswordScenarioCBoundaryTest {
  private static final String ACCOUNT = "11111111-1111-4111-8111-111111111111";
  private static final String REALM = "22222222-2222-4222-8222-222222222222";
  private static final String SUBJECT = "33333333-3333-4333-8333-333333333333";
  private static final String CURRENT = "Synthetic-current-password";
  private static final String CHOSEN = "Synthetic-chosen-password";
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

  @Test
  void publicPasswordFreezesEncryptedIntentAndClearsSetupOnlyAfterVerifiedNativeOldPasswordReceipt()
      throws Exception {
    try (var f = new Fixture(true, true)) {
      f.expectNative();
      assertDoesNotThrow(
          () ->
              assertEquals(200, f.controller.updatePassword(f.password()).getStatusCode().value()));
      assertEquals("ACKNOWLEDGED", f.status());
      assertEquals(1, f.effects.get());
      assertEquals(1, f.clears());
      String stored = f.jdbc.queryForList("select * from identity_self_password_intent").toString();
      for (String secret : List.of(CURRENT, CHOSEN, "fixture-human", "fixture-admin"))
        assertFalse(stored.contains(secret));
      assertEquals(200, f.controller.updatePassword(f.password()).getStatusCode().value());
      assertEquals(1, f.clears());
      verifyNoInteractions(f.legacy);
      f.server.verify();
    }
    try (var unassigned = new Fixture(true, true, null)) {
      unassigned.expectNative();
      assertEquals(
          200, unassigned.controller.updatePassword(unassigned.password()).getStatusCode().value());
      JsonNode metadata =
          unassigned.codec.read(
              unassigned.jdbc.queryForObject(
                  "select metadata_json from identity_self_password_intent where operation_id=?",
                  String.class,
                  unassigned.operation),
              JsonNode.class);
      assertTrue(metadata.has("tenantId"));
      assertTrue(metadata.get("tenantId").isNull());
      assertEquals("ACKNOWLEDGED", unassigned.status());
      assertEquals(1, unassigned.clears());
      unassigned.server.verify();
    }
  }

  @Test
  void publicPasswordSupportsEveryOwnedKindAndLegitimateAdministratorConsultantPair()
      throws Exception {
    for (String kind :
        List.of("USER", "CONSULTANT", "AGENCY_ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN")) {
      boolean admin = kind.endsWith("ADMIN");
      for (boolean dual : admin ? List.of(false, true) : List.of(false)) {
        try (var f = new Fixture(true, true, "PLATFORM_ADMIN".equals(kind) ? 0L : 7L, kind, dual)) {
          f.expectNative();
          var response = assertDoesNotThrow(() -> f.controller.updatePassword(f.password()));
          assertEquals(200, response.getStatusCode().value(), kind + "/" + dual);
          assertEquals("ACKNOWLEDGED", f.status());
          assertEquals(1, f.effects.get());
          assertEquals(dual || "CONSULTANT".equals(kind) ? 1 : 0, f.clears());
          var origin = f.frozenBodyFromIndependentDecryption().path("target").path("origin");
          if (Set.of("TENANT_ADMIN", "PLATFORM_ADMIN").contains(kind))
            assertEquals(
                "PROTECTED_ACCOUNT_OPERATIONS_V1", origin.path("humanAuthority").textValue());
          else assertTrue(origin.path("humanAuthority").isNull());
          verifyNoInteractions(f.legacy);
          f.server.verify();
        }
      }
    }
  }

  @Test
  void unsignedOrForgedPolicy400KeepsTheActualSelfPasswordIntentFrozen() {
    for (int refusal : List.of(1, 3)) {
      try (var f = new Fixture(true, true)) {
        f.policyResponse = refusal;
        f.expectNative();
        assertThrows(
            ServiceUnavailableException.class, () -> f.controller.updatePassword(f.password()));
        assertEquals("FROZEN", f.status());
        assertEquals(0, f.clears());
        assertEquals(0, f.effects.get());
        f.server.verify();
      }
    }
  }

  @Test
  void signedPolicy400StoresItsExactTerminalReceiptBeforeSelfPasswordRefusal() {
    try (var f = new Fixture(true, true)) {
      f.policyResponse = 2;
      f.expectNative();
      assertThrows(BadRequestException.class, () -> f.controller.updatePassword(f.password()));
      assertEquals("REJECTED", f.status());
      String terminal =
          f.jdbc.queryForObject("select receipt from identity_self_password_intent", String.class);
      assertNotNull(terminal);
      assertTrue(terminal.contains("NO_EFFECT"));
      assertThrows(BadRequestException.class, () -> f.controller.updatePassword(f.password()));
      assertEquals(0, f.clears());
      assertEquals(0, f.effects.get());
      f.server.verify();
    }
  }

  @Test
  void signedNonPolicySelfRefusalKeepsItsOriginalKeyAndPublicStatus() {
    for (int status : List.of(403, 409)) {
      try (var f = new Fixture(true, true)) {
        f.policyResponse = 2;
        f.policyStatus = status;
        f.expectNative();
        Class<? extends RuntimeException> expected = status == 403
            ? de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class
            : ConflictException.class;
        assertThrows(expected, () -> f.controller.updatePassword(f.password()));
        assertEquals("REFUSED", f.status());
        assertThrows(expected, () -> f.controller.updatePassword(f.password()));
        assertEquals(0, f.effects.get());
        assertEquals(0, f.clears());
        f.server.verify();
      }
    }
  }

  @Test
  void unsupportedOuterEnvelopeVersionCannotResumeUnknownPasswordOrClearSetup() {
    try (var f = new Fixture(true, true)) {
      f.expectNative(true);
      assertThrows(
          ServiceUnavailableException.class, () -> f.controller.updatePassword(f.password()));
      assertEquals("FROZEN", f.status());
      assertEquals(0, f.clears());
      assertEquals(1, f.effects.get());
      String saved =
          f.jdbc.queryForObject(
              "select intent_envelope from identity_self_password_intent where operation_id=?",
              String.class,
              f.operation);
      var envelope = f.codec.read(saved, ObjectNode.class);
      envelope.put("v", 4294967297L);
      f.jdbc.update(
          "update identity_self_password_intent set intent_envelope=? where operation_id=?",
          f.codec.canonical(envelope),
          f.operation);
      assertThrows(
          ServiceUnavailableException.class, () -> f.controller.updatePassword(f.password()));
      assertEquals("FROZEN", f.status());
      assertEquals(0, f.clears());
      assertEquals(1, f.effects.get());
      verifyNoInteractions(f.legacy);
      f.server.verify();
    }
  }

  @Test
  void missingScopedPasswordOwnerRefusesBeforeLegacyOrSetupMutation() {
    try (var f = new Fixture(true, false)) {
      assertThrows(
          ServiceUnavailableException.class, () -> f.controller.updatePassword(f.password()));
      assertEquals(0, f.clears());
      verifyNoInteractions(f.legacy);
    }
  }

  @Test
  void samePasswordConflictKeepsExistingSetupRequirementAndDoesNotCallIdentity() {
    try (var f = new Fixture(true, true)) {
      var dto = f.password();
      dto.setNewPassword(CURRENT);
      assertThrows(ConflictException.class, () -> f.controller.updatePassword(dto));
      assertEquals(0, f.clears());
      verifyNoInteractions(f.legacy);
    }
  }

  @Test
  void legacyPasswordRetainsCurrentPasswordValidationAndKnownSuccessCleanup() {
    try (var f = new Fixture(false, false)) {
      when(f.legacy.validatePasswordIgnoring2fa(anyString(), eq(CURRENT))).thenReturn(true);
      when(f.legacy.changePassword(ACCOUNT, CHOSEN)).thenReturn(true);
      assertEquals(200, f.controller.updatePassword(f.password()).getStatusCode().value());
      verify(f.legacy).validatePasswordIgnoring2fa(anyString(), eq(CURRENT));
      verify(f.legacy).changePassword(ACCOUNT, CHOSEN);
      assertEquals(1, f.clears());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final UserController controller = mock(UserController.class, CALLS_REAL_METHODS);
    final IdentityManaging legacy = mock(IdentityManaging.class);
    final JdbcTemplate jdbc;
    final RestTemplate http = new RestTemplate();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    final AtomicInteger effects = new AtomicInteger();
    int policyResponse;
    int policyStatus = 400;
    final Long tenant;
    final String ownedKind;
    final boolean ownsConsultant;
    final String operation = UUID.randomUUID().toString();
    final AccountLifecycleCodec codec = new AccountLifecycleCodec(KEY);
    final TaskActorCredentials actor =
        new TaskActorCredentials(
            TaskActor.ACCOUNT_ADMIN, "backend-account-admin", "fixture-secret", SUBJECT);

    Fixture(boolean five, boolean installed) {
      this(five, installed, 7L);
    }

    Fixture(boolean five, boolean installed, Long tenant) {
      this(five, installed, tenant, "CONSULTANT", false);
    }

    Fixture(boolean five, boolean installed, Long tenant, String kind, boolean dual) {
      this.tenant = tenant;
      this.ownedKind = kind;
      this.ownsConsultant = dual || "CONSULTANT".equals(kind);
      var source =
          new DriverManagerDataSource(
              "jdbc:h2:mem:password-"
                  + UUID.randomUUID()
                  + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
              "sa",
              "");
      jdbc = new JdbcTemplate(source);
      jdbc.execute(
          "create table consultant(consultant_id varchar(36) primary key,tenant_id bigint,delete_date timestamp,username varchar(512),password_change_required boolean,update_date timestamp,clear_count int,email varchar(320))");
      jdbc.update(
          "insert into consultant values(?,?,null,'ordinary',true,?,0,'fixture@example.org')",
          ACCOUNT,
          tenant,
          java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
      jdbc.execute(
          "create table user(user_id varchar(36) primary key,tenant_id bigint,delete_date timestamp,username varchar(512),email varchar(320))");
      jdbc.execute(
          "create table admin(admin_id varchar(36) primary key,tenant_id bigint,type varchar(16),email varchar(320))");
      if (!"CONSULTANT".equals(kind) && !dual) jdbc.update("delete from consultant");
      if ("USER".equals(kind))
        jdbc.update(
            "insert into user values(?,?,null,'ordinary','fixture@example.org')", ACCOUNT, tenant);
      if (kind.endsWith("ADMIN"))
        jdbc.update(
            "insert into admin values(?,?,?,'fixture@example.org')",
            ACCOUNT,
            tenant,
            switch (kind) {
              case "AGENCY_ADMIN" -> "AGENCY";
              case "TENANT_ADMIN" -> "TENANT";
              case "PLATFORM_ADMIN" -> "SUPER";
              default -> throw new IllegalArgumentException();
            });
      jdbc.execute(
          "create table identity_self_password_intent(operation_id varchar(36) primary key,realm_id varchar(512),account_id varchar(36),tenant_id bigint,owner_client varchar(255),owner_subject varchar(36),request_digest varchar(64),target_json clob,authority_valid_until bigint,metadata_json clob,intent_envelope clob,status varchar(32),receipt clob,updated_at timestamp)");
      var human =
          new AuthenticatedUser(
              ACCOUNT,
              "ordinary",
              Set.of("consultant"),
              "fixture-human",
              Set.of("ROLE_CONSULTANT"));
      human.setTenantId(tenant);
      var request = new MockHttpServletRequest("PUT", "/service/users/password/change");
      var jwtBuilder =
          Jwt.withTokenValue("fixture-human")
              .header("alg", "RS256")
              .subject(ACCOUNT)
              .issuer("https://public.identity.test/realms/fixture")
              .claim("azp", "app")
              .issuedAt(Instant.now().minusSeconds(10))
              .expiresAt(Instant.now().plusSeconds(600));
      if (tenant != null) jwtBuilder.claim("tenantId", tenant.toString());
      var jwt = jwtBuilder.build();
      request.setUserPrincipal(
          new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_CONSULTANT"))));
      request.addHeader("Authorization", "Bearer fixture-human");
      request.addHeader("Idempotency-Key", operation);
      var mode = mock(IdentityClientConfig.class);
      when(mode.isFiveActorMode()).thenReturn(five);
      var actors = mock(TaskActorConfiguration.class);
      when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(actor);
      var broker = mock(TaskActorTokenBroker.class);
      var checked = mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
      when(checked.purpose()).thenReturn(TaskPurpose.ACCOUNT_MAINTENANCE);
      when(checked.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);
      when(checked.accessToken()).thenReturn("fixture-admin");
      when(checked.issuer()).thenReturn("https://public.identity.test/realms/fixture");
      when(checked.clientId()).thenReturn(actor.clientId());
      when(checked.subject()).thenReturn(SUBJECT);
      when(checked.validatedJwtIssuedAt())
          .thenReturn(Instant.now().minusSeconds(1).getEpochSecond());
      when(checked.validatedJwtExpiresAt())
          .thenReturn(Instant.now().plusSeconds(300).getEpochSecond());
      when(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(checked);
      var consultants = mock(ConsultantService.class);
      when(consultants.getConsultant(ACCOUNT))
          .thenAnswer(
              ignored -> {
                if (jdbc.queryForObject(
                        "select count(*) from consultant where consultant_id=?",
                        Integer.class,
                        ACCOUNT)
                    == 0) return Optional.empty();
                var c = new Consultant();
                c.setId(ACCOUNT);
                c.setPasswordChangeRequired(
                    jdbc.queryForObject(
                        "select password_change_required from consultant where consultant_id=?",
                        Boolean.class,
                        ACCOUNT));
                return Optional.of(c);
              });
      when(consultants.saveConsultant(any(Consultant.class)))
          .thenAnswer(
              call -> {
                jdbc.update(
                    "update consultant set password_change_required=false,clear_count=clear_count+1 where consultant_id=?",
                    ACCOUNT);
                return call.getArgument(0);
              });
      var policy = mock(IdentityPolicy.class);
      var delegate =
          new UserAccountControllerDelegate(
              mock(UserAccountService.class),
              human,
              mock(DecryptionService.class),
              mock(ConsultantDataFacade.class),
              policy,
              legacy,
              mock(AccountManaging.class),
              mock(Messaging.class),
              mock(ConsultantDtoMapper.class),
              new UserDtoMapper(policy),
              consultants,
              mock(ConsultantUpdateService.class),
              mock(ConsultantDataProvider.class),
              mock(AskerDataProvider.class),
              mock(VideoChatConfig.class),
              mock(KeycloakUserDataProvider.class),
              mock(AgencyAdminDataProvider.class),
              new UsernameTranscoder());
      delegate.setPasswordIdentityConfig(mode);
      if (installed)
        delegate.setSelfPasswordMaintenance(
            new SelfPasswordMaintenance(
                mode,
                human,
                request,
                jdbc,
                new DataSourceTransactionManager(source),
                actors,
                broker,
                codec,
                http,
                Clock.systemUTC(),
                "https://internal.identity.test",
                "https://public.identity.test/realms/fixture",
                "fixture",
                REALM));
      ReflectionTestUtils.setField(controller, "userAccountControllerDelegate", delegate);
    }

    PasswordDTO password() {
      var dto = new PasswordDTO();
      dto.setOldPassword(CURRENT);
      dto.setNewPassword(CHOSEN);
      return dto;
    }

    int clears() {
      return jdbc.queryForObject(
          "select coalesce(sum(clear_count),0) from consultant where consultant_id=?",
          Integer.class,
          ACCOUNT);
    }

    String status() {
      return jdbc.queryForObject(
          "select status from identity_self_password_intent where operation_id=?",
          String.class,
          operation);
    }

    JsonNode frozenBodyFromIndependentDecryption() {
      var saved =
          jdbc.queryForMap(
              "select metadata_json,intent_envelope from identity_self_password_intent where operation_id=?",
              operation);
      JsonNode metadata = codec.read((String) saved.get("metadata_json"), JsonNode.class);
      JsonNode envelope = codec.read((String) saved.get("intent_envelope"), JsonNode.class);
      assertEquals(16, metadata.size());
      assertEquals(1, envelope.path("v").intValue());
      try {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        String digest =
            HexFormat.of()
                .formatHex(
                    mac.doFinal(
                        ("oriso/user-maintenance-intent/metadata/v1\n" + codec.canonical(metadata))
                            .getBytes(StandardCharsets.UTF_8)));
        byte[] key =
            mac.doFinal(
                ("oriso/user-maintenance-intent/encryption/v1\n" + digest)
                    .getBytes(StandardCharsets.UTF_8));
        byte[] nonce = Base64.getUrlDecoder().decode(envelope.path("nonce").textValue());
        assertEquals(12, nonce.length);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
            Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(codec.canonical(metadata).getBytes(StandardCharsets.UTF_8));
        var plain =
            codec.read(
                new String(
                    cipher.doFinal(
                        Base64.getUrlDecoder().decode(envelope.path("ciphertext").textValue())),
                    StandardCharsets.UTF_8),
                JsonNode.class);
        Arrays.fill(key, (byte) 0);
        assertEquals(ownedKind, plain.path("localSetup").path("kind").textValue());
        if (ownsConsultant)
          assertTrue(plain.path("localSetup").path("passwordChangeRequired").booleanValue());
        else assertTrue(plain.path("localSetup").path("passwordChangeRequired").isNull());
        return plain.path("body");
      } catch (java.security.GeneralSecurityException failure) {
        throw new AssertionError("Private password intent could not be verified");
      }
    }

    void expectNative() {
      expectNative(false);
    }

    void expectNative(boolean unknownFirst) {
      String endpoint =
          "https://internal.identity.test/realms/fixture/oriso-account-maintenance/v1/accounts/"
              + ACCOUNT;
      server
          .expect(requestTo(endpoint + "/read"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(header("Authorization", "Bearer fixture-admin"))
          .andExpect(header("X-ORISO-Human-Authorization", "Bearer fixture-human"))
          .andRespond(
              out -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("PREPARED", status());
                assertEquals(0, clears());
                var read = new TreeMap<String, Object>();
                read.put("id", ACCOUNT);
                read.put("username", "ordinary");
                read.put("email", null);
                read.put("firstName", null);
                read.put("lastName", null);
                read.put("enabled", true);
                read.put("emailVerified", false);
                read.put("tenantId", tenant == null ? null : tenant.toString());
                read.put("preferredLanguage", null);
                read.put("effectiveRoles", List.of("consultant"));
                read.put("passwordChangeRequired", true);
                read.put("state", "a".repeat(64));
                return withSuccess(codec.canonical(read), MediaType.APPLICATION_JSON)
                    .createResponse(out);
              });
      server
          .expect(
              unknownFirst ? ExpectedCount.between(1, 2) : ExpectedCount.once(),
              requestTo(endpoint + "/self-password"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(header("Authorization", "Bearer fixture-admin"))
          .andRespond(
              out -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("FROZEN", status());
                assertEquals(0, clears());
                if (policyResponse == 1)
                  return withStatus(HttpStatus.valueOf(policyStatus))
                      .body("{\"error\":\"Password rejected\"}")
                      .contentType(MediaType.APPLICATION_JSON)
                      .createResponse(out);
                int nativeAttempt = policyResponse == 0 ? effects.incrementAndGet() : 0;
                if (unknownFirst && nativeAttempt == 1)
                  return withStatus(HttpStatus.SERVICE_UNAVAILABLE).createResponse(out);
                JsonNode body =
                    codec.read(((MockClientHttpRequest) out).getBodyAsString(), JsonNode.class);
                assertEquals(CURRENT, body.path("change").path("currentPassword").textValue());
                assertEquals(CHOSEN, body.path("change").path("password").textValue());
                assertEquals(frozenBodyFromIndependentDecryption(), body);
                long expiry =
                    jdbc.queryForObject(
                        "select authority_valid_until from identity_self_password_intent where operation_id=?",
                        Long.class,
                        operation);
                assertTrue(expiry > Instant.now().getEpochSecond());
                assertTrue(expiry <= Instant.now().plusSeconds(300).getEpochSecond());
                var facts = new TreeMap<String, Object>();
                facts.put("realmId", REALM);
                facts.put("accountId", ACCOUNT);
                facts.put("operationId", operation);
                facts.put("command", "self-password");
                facts.put("ownerClient", actor.clientId());
                facts.put("ownerSubject", SUBJECT);
                facts.put("intentDigest", codec.digest("maintenance-intent", body));
                facts.put("previousState", body.path("expectedState").textValue());
                facts.put("tenantId", tenant == null ? null : tenant.toString());
                facts.put(
                    "originDigest",
                    codec.digest("maintenance-origin", body.path("target").path("origin")));
                String result = policyResponse == 0 ? "UPDATED" : "NO_EFFECT";
                facts.put("result", result);
                if (policyResponse != 0) facts.put("status", policyStatus);
                String encoded =
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(codec.canonical(facts).getBytes(StandardCharsets.UTF_8));
                try {
                  var mac = Mac.getInstance("HmacSHA256");
                  mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
                  String receipt =
                      encoded
                          + "."
                          + HexFormat.of()
                              .formatHex(
                                  mac.doFinal(
                                      ((policyResponse == 0
                                                  ? "maintenance-operation-receipt\n"
                                                  : "maintenance-no-effect-receipt/v1\n")
                                              + encoded)
                                          .getBytes(StandardCharsets.UTF_8)));
                  if (policyResponse == 3)
                    receipt =
                        receipt.substring(0, receipt.length() - 1)
                            + (receipt.endsWith("0") ? "1" : "0");
                  var reply = new TreeMap<String, Object>();
                  reply.put("operationId", operation);
                  reply.put("accountId", ACCOUNT);
                  reply.put("result", result);
                  reply.put("receipt", receipt);
                  if (policyResponse != 0) reply.put("status", policyStatus);
                  return withStatus(policyResponse == 0 ? HttpStatus.OK : HttpStatus.valueOf(policyStatus))
                      .body(codec.canonical(reply))
                      .contentType(MediaType.APPLICATION_JSON)
                      .createResponse(out);
                } catch (Exception e) {
                  throw new IllegalStateException("Synthetic receipt unavailable");
                }
              });
    }

    public void close() {
      jdbc.execute("shutdown");
    }
  }
}
