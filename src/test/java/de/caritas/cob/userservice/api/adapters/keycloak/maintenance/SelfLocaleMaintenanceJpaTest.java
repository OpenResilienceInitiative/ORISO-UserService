package de.caritas.cob.userservice.api.adapters.keycloak.maintenance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.*;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/** Actual JPA/UserRepository/AccountManager; separate from public and native pairing proof. */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SelfLocaleMaintenanceJpaTest {
  private static final String ACCOUNT = "11111111-1111-4111-8111-111111111111";
  private static final String REALM = "22222222-2222-4222-8222-222222222222";
  private static final String ENDPOINT =
      "https://identity.test/realms/fixture/oriso-account-maintenance/v1/accounts/" + ACCOUNT;
  @Autowired private DataSource dataSource;
  @Autowired private UserRepository users;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private EntityManager entityManager;
  private JdbcTemplate jdbc;
  private SelfLocaleMaintenance maintenance;
  private AccountManager accounts;
  private MockRestServiceServer server;
  private final String operation = UUID.randomUUID().toString();
  private final AtomicInteger nativeMutations = new AtomicInteger();

  @BeforeEach
  void actualJpaOwnerWithNoOuterTestTransaction() {
    assertInstanceOf(JpaTransactionManager.class, transactions);
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("drop table if exists identity_account_operation_intent");
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "db/changelog/changeset/20261021_identity_self_locale_367/migrate.sql"),
            new ClassPathResource(
                "db/changelog/changeset/20261023_existing_import_intent_367/migrate.sql"))
        .execute(dataSource);
    users.findById(ACCOUNT).ifPresent(users::delete);
    var user = new User(ACCOUNT, null, "ordinary-jpa", "ordinary@fixture.invalid", false);
    user.setTenantId(7L);
    user.setLanguageCode(LanguageCode.en);
    user.setEncourage2fa(false);
    users.save(user);
    var human =
        new AuthenticatedUser(
            ACCOUNT, "ordinary-jpa", Set.of("user"), "fixture-human", Set.of("ROLE_USER"));
    human.setTenantId(7L);
    var request = new MockHttpServletRequest("PATCH", "/service/users/data");
    var jwt =
        Jwt.withTokenValue("fixture-human")
            .header("alg", "RS256")
            .subject(ACCOUNT)
            .issuer("https://identity.test/realms/fixture")
            .claim("azp", "app")
            .claim("tenantId", "7")
            .issuedAt(Instant.now().minusSeconds(10))
            .expiresAt(Instant.now().plusSeconds(600))
            .build();
    request.setUserPrincipal(
        new JwtAuthenticationToken(
            jwt,
            List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    "ROLE_USER"))));
    request.addHeader("Authorization", "Bearer fixture-human");
    request.addHeader("Idempotency-Key", operation);
    var mode = mock(IdentityClientConfig.class);
    when(mode.isFiveActorMode()).thenReturn(true);
    var actors = mock(TaskActorConfiguration.class);
    when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE))
        .thenReturn(
            new TaskActorCredentials(
                TaskActor.ACCOUNT_ADMIN,
                "backend-account-admin",
                "fixture-secret",
                "33333333-3333-4333-8333-333333333333"));
    var broker = mock(TaskActorTokenBroker.class);
    var checked = mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
    when(checked.accessToken()).thenReturn("fixture-admin");
    when(checked.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);
    when(checked.purpose()).thenReturn(TaskPurpose.ACCOUNT_MAINTENANCE);
    when(checked.clientId()).thenReturn("backend-account-admin");
    when(checked.subject()).thenReturn("33333333-3333-4333-8333-333333333333");
    when(checked.issuer()).thenReturn("https://identity.test/realms/fixture");
    when(checked.validatedJwtIssuedAt()).thenReturn(Instant.now().minusSeconds(1).getEpochSecond());
    when(checked.validatedJwtExpiresAt())
        .thenReturn(Instant.now().plusSeconds(60).getEpochSecond());
    when(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(checked);
    when(broker.issue(TaskPurpose.ACCOUNT_MAINTENANCE))
        .thenReturn(
            new TaskActorTokenBroker.TaskToken(TaskActor.ACCOUNT_ADMIN, "fixture-admin", 60));
    var http = new RestTemplate();
    server = MockRestServiceServer.bindTo(http).build();
    maintenance =
        new SelfLocaleMaintenance(
            mode,
            human,
            request,
            jdbc,
            transactions,
            actors,
            broker,
            new AccountLifecycleCodec(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)),
            http,
            Clock.systemUTC(),
            "https://identity.test",
            "fixture",
            REALM);
    var transcoder = new UsernameTranscoder();
    accounts =
        new AccountManager(
            mock(ConsultantRepository.class),
            mock(AdminRepository.class),
            users,
            new UserServiceMapper(transcoder),
            transcoder,
            mock(AgencyService.class),
            mock(TenantService.class),
            mock(ConsultantAgencyRepository.class),
            human,
            mock(SessionRepository.class),
            mock(AppointmentService.class),
            mock(PatchConsultantSaga.class));
  }

  @Test
  void actualJpaCommitPrecedesNativeDispatchEvenWhenReplyIsUnknown() {
    expectRead();
    server
        .expect(requestTo(ENDPOINT + "/locale"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer fixture-admin"))
        .andExpect(header("X-ORISO-Human-Authorization", "Bearer fixture-human"))
        .andRespond(
            outbound -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals(
                  LanguageCode.de, users.findById(ACCOUNT).orElseThrow().getLanguageCode());
              assertTrue(users.findById(ACCOUNT).orElseThrow().getEncourage2fa());
              assertEquals(
                  1,
                  jdbc.queryForObject(
                      "select count(*) from identity_account_operation_intent where operation_id=? and status='FROZEN' and domain_applied=true",
                      Integer.class,
                      operation));
              nativeMutations.incrementAndGet();
              throw new ResourceAccessException("Fixture lost a native reply");
            });
    var patch =
        Map.<String, Object>of("id", ACCOUNT, "preferredLanguage", "de", "encourage2fa", true);
    assertThrows(
        RuntimeException.class,
        () -> maintenance.patch("de", patch, () -> accounts.patchUser(patch)));
    assertEquals("FROZEN", status());
    assertEquals(1, nativeMutations.get());
    server.verify();
  }

  @Test
  void actualJpaFlushFailureRollsBackDomainAndPreventsDispatchableIntent() {
    expectRead();
    var patch =
        Map.<String, Object>of("id", ACCOUNT, "preferredLanguage", "de", "encourage2fa", true);
    assertThrows(
        RuntimeException.class,
        () ->
            maintenance.patch(
                "de",
                patch,
                () -> {
                  assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                  accounts.patchUser(patch);
                  entityManager.flush();
                  assertEquals(
                      "de",
                      jdbc.queryForObject(
                          "select language_code from user where user_id=?", String.class, ACCOUNT));
                  throw new IllegalStateException("Fixture failed after actual JPA flush");
                }));
    assertEquals(LanguageCode.en, users.findById(ACCOUNT).orElseThrow().getLanguageCode());
    assertFalse(users.findById(ACCOUNT).orElseThrow().getEncourage2fa());
    assertEquals("PREPARED", status());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from identity_account_operation_intent where operation_id=? and status='PREPARED' and domain_applied=false and intent_body is null and receipt is null",
            Integer.class,
            operation));
    assertEquals(0, nativeMutations.get());
    server.verify();
  }

  @Test
  void anIdenticalSavedImportRowCanNeverResumeAsASelfLocaleOperation() {
    expectRead();
    server
        .expect(requestTo(ENDPOINT + "/locale"))
        .andRespond(
            outbound -> {
              throw new ResourceAccessException("Fixture lost reply");
            });
    var patch =
        Map.<String, Object>of("id", ACCOUNT, "preferredLanguage", "de", "encourage2fa", true);
    assertThrows(
        RuntimeException.class,
        () -> maintenance.patch("de", patch, () -> accounts.patchUser(patch)));
    assertEquals("FROZEN", status());
    assertEquals(
        "SELF_LOCALE",
        jdbc.queryForObject(
            "select operation_kind from identity_account_operation_intent where operation_id=?",
            String.class,
            operation));
    jdbc.update(
        "update identity_account_operation_intent set operation_kind='EXISTING_CONSULTANT_IMPORT',status='ACKNOWLEDGED',receipt='synthetic-other-namespace' where operation_id=?",
        operation);
    var frozen =
        jdbc.queryForMap(
            "select * from identity_account_operation_intent where operation_id=?", operation);
    assertThrows(
        RuntimeException.class,
        () ->
            maintenance.patch(
                "de",
                patch,
                () -> {
                  fail("Foreign operation cannot invoke domain callback");
                  return java.util.Optional.empty();
                }));
    assertEquals(
        frozen,
        jdbc.queryForMap(
            "select * from identity_account_operation_intent where operation_id=?", operation));
    assertEquals(LanguageCode.de, users.findById(ACCOUNT).orElseThrow().getLanguageCode());
    server.verify();
  }

  private void expectRead() {
    server
        .expect(requestTo(ENDPOINT + "/read"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            outbound -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals("PREPARED", status());
              var result = new LinkedHashMap<String, Object>();
              result.put("id", ACCOUNT);
              result.put("username", "ordinary-jpa");
              result.put("email", null);
              result.put("firstName", null);
              result.put("lastName", null);
              result.put("enabled", true);
              result.put("emailVerified", false);
              result.put("tenantId", "7");
              result.put("preferredLanguage", "en");
              result.put("effectiveRoles", List.of("user"));
              result.put("passwordChangeRequired", false);
              result.put("state", "a".repeat(64));
              return withSuccess(
                      new AccountLifecycleCodec(
                              "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))
                          .canonical(result),
                      MediaType.APPLICATION_JSON)
                  .createResponse(outbound);
            });
  }

  private String status() {
    return jdbc.queryForObject(
        "select status from identity_account_operation_intent where operation_id=?",
        String.class,
        operation);
  }

  @Test
  void signedLocaleForbiddenIsCommittedAndReplayedWithoutRepeatingTheLocalPatch() throws Exception {
    assertSignedTerminal(403);
  }

  @Test
  void signedLocaleConflictIsCommittedAndReplayedWithoutRepeatingTheLocalPatch() throws Exception {
    assertSignedTerminal(409);
  }

  private void assertSignedTerminal(int status) throws Exception {
    expectRead();
    server.expect(requestTo(ENDPOINT + "/locale")).andRespond(noEffect(status, "valid"));
    var patch = Map.<String, Object>of("id", ACCOUNT, "preferredLanguage", "de", "encourage2fa", true);
    var domainCalls = new AtomicInteger();
    java.util.function.Supplier<Optional<Map<String, Object>>> callback = () -> {
      domainCalls.incrementAndGet();
      return accounts.patchUser(patch);
    };
    RuntimeException refusal = assertThrows(RuntimeException.class,
        () -> maintenance.patch("de", patch, callback));
    assertInstanceOf(status == 403 ? ForbiddenException.class : ConflictException.class, refusal);
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    assertEquals("REFUSED", status());
    assertEquals(1, domainCalls.get());
    // NO_EFFECT describes the native identity command. The original mixed local PATCH stays committed.
    assertEquals(LanguageCode.de, users.findById(ACCOUNT).orElseThrow().getLanguageCode());
    assertTrue(users.findById(ACCOUNT).orElseThrow().getEncourage2fa());
    var original = jdbc.queryForMap("select * from identity_account_operation_intent where operation_id=?", operation);
    assertNotNull(original.get("receipt"));
    assertMutationHeaders(refusal, status, operation, true);
    for (String key : Arrays.asList(null, UUID.randomUUID().toString(), operation + " "))
      assertMutationHeaders(refusal, status, key, false);
    RuntimeException replay = assertThrows(RuntimeException.class,
        () -> maintenance.patch("de", patch, () -> { fail("Terminal replay repeated the local PATCH"); return Optional.empty(); }));
    assertMutationHeaders(replay, status, operation, true);
    assertEquals(original, jdbc.queryForMap("select * from identity_account_operation_intent where operation_id=?", operation));
    assertEquals(1, domainCalls.get());
    server.verify();
  }

  @Test
  void unsignedForeignOrWrongMacLocaleErrorsCannotRetireTheFrozenOperation() throws Exception {
    expectRead();
    for (String scenario : List.of("unsigned", "wrong-mac", "foreign-intent", "status-mismatch", "invalid-400"))
      server.expect(requestTo(ENDPOINT + "/locale")).andRespond(noEffect(403, scenario));
    var patch = Map.<String, Object>of("id", ACCOUNT, "preferredLanguage", "de", "encourage2fa", true);
    var domainCalls = new AtomicInteger();
    for (int attempt = 0; attempt < 5; attempt++) {
      var failure = assertThrows(ServiceUnavailableException.class,
          () -> maintenance.patch("de", patch, () -> { domainCalls.incrementAndGet(); return accounts.patchUser(patch); }));
      assertEquals("FROZEN", status());
      assertNull(jdbc.queryForObject("select receipt from identity_account_operation_intent where operation_id=?", String.class, operation));
      assertEquals(1, domainCalls.get());
      assertMutationHeaders(failure, 503, operation, false);
    }
    server.verify();
  }

  @Test
  void terminalReceiptTamperingAndOriginalOwnerBirthDriftCannotProduceMutationHeaders() throws Exception {
    expectRead();
    server.expect(requestTo(ENDPOINT + "/locale")).andRespond(noEffect(409, "valid"));
    var patch = Map.<String, Object>of("id", ACCOUNT, "preferredLanguage", "de");
    assertInstanceOf(ConflictException.class, assertThrows(RuntimeException.class,
        () -> maintenance.patch("de", patch, () -> accounts.patchUser(patch))));
    assertEquals("REFUSED", status());
    String receipt = jdbc.queryForObject("select receipt from identity_account_operation_intent where operation_id=?", String.class, operation);
    jdbc.update("update identity_account_operation_intent set receipt='{}' where operation_id=?", operation);
    var corrupted = assertThrows(RuntimeException.class, () -> maintenance.patch("de", patch, Optional::empty));
    assertMutationHeaders(corrupted, 503, operation, false);
    jdbc.update("update identity_account_operation_intent set receipt=? where operation_id=?", receipt, operation);
    jdbc.update("update user set create_date=? where user_id=?", java.sql.Timestamp.valueOf("2026-10-01 01:02:03"), ACCOUNT);
    var replaced = assertThrows(ConflictException.class, () -> maintenance.patch("de", patch, Optional::empty));
    assertMutationHeaders(replaced, 409, operation, false);
    server.verify();
  }

  @Test
  void failedTerminalSqlCommitPreservesTheFrozenIntentWithoutDefiniteOutcomeHeaders() throws Exception {
    expectRead();
    server.expect(requestTo(ENDPOINT + "/locale")).andRespond(noEffect(409, "valid"));
    server.expect(requestTo(ENDPOINT + "/locale")).andRespond(noEffect(409, "valid"));
    jdbc.execute("alter table identity_account_operation_intent add constraint fixture_refusal_commit check(status <> 'REFUSED')");
    var patch = Map.<String, Object>of("id", ACCOUNT, "preferredLanguage", "de");
    var failure = assertThrows(ServiceUnavailableException.class,
        () -> maintenance.patch("de", patch, () -> accounts.patchUser(patch)));
    assertEquals("FROZEN", status());
    assertNull(jdbc.queryForObject("select receipt from identity_account_operation_intent where operation_id=?", String.class, operation));
    assertEquals(LanguageCode.de, users.findById(ACCOUNT).orElseThrow().getLanguageCode());
    assertMutationHeaders(failure, 503, operation, false);
    jdbc.execute("alter table identity_account_operation_intent drop constraint fixture_refusal_commit");
    var retry = assertThrows(RuntimeException.class,
        () -> maintenance.patch("de", patch, () -> { fail("Retry repeated the committed local PATCH"); return Optional.empty(); }));
    assertEquals("REFUSED", status());
    assertMutationHeaders(retry, 409, operation, true);
    server.verify();
  }

  @Test
  void acknowledgedLocaleReplayRechecksTheOriginalNativeCommandWithoutRepeatingLocalChanges() {
    expectRead();
    server.expect(requestTo(ENDPOINT + "/locale")).andRespond(noEffect(200, "updated"));
    server.expect(requestTo(ENDPOINT + "/locale")).andRespond(noEffect(200, "updated"));
    var patch = Map.<String, Object>of("id", ACCOUNT, "preferredLanguage", "de");
    var calls = new AtomicInteger();
    java.util.function.Supplier<Optional<Map<String, Object>>> callback = () -> {
      calls.incrementAndGet();
      return accounts.patchUser(patch);
    };
    assertDoesNotThrow(() -> maintenance.patch("de", patch, callback));
    var original = jdbc.queryForMap("select * from identity_account_operation_intent where operation_id=?", operation);
    assertDoesNotThrow(() -> maintenance.patch("de", patch, callback));
    assertEquals(1, calls.get());
    assertEquals(original, jdbc.queryForMap("select * from identity_account_operation_intent where operation_id=?", operation));
    server.verify();
  }

  @Test
  void revokedNativeLocaleGrantCannotReplayLocalAcknowledgementAsSuccess() throws Exception {
    expectRead();
    server.expect(requestTo(ENDPOINT + "/locale")).andRespond(noEffect(200, "updated"));
    server.expect(requestTo(ENDPOINT + "/locale")).andRespond(
        org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(HttpStatus.FORBIDDEN).body("{}"));
    var patch = Map.<String, Object>of("id", ACCOUNT, "preferredLanguage", "de");
    assertDoesNotThrow(() -> maintenance.patch("de", patch, () -> accounts.patchUser(patch)));
    var original = jdbc.queryForMap("select * from identity_account_operation_intent where operation_id=?", operation);
    var failure = assertThrows(ServiceUnavailableException.class,
        () -> maintenance.patch("de", patch, () -> { fail("Acknowledged replay repeated the local PATCH"); return Optional.empty(); }));
    assertMutationHeaders(failure, 503, operation, false);
    assertEquals(original, jdbc.queryForMap("select * from identity_account_operation_intent where operation_id=?", operation));
    server.verify();
  }

  private ResponseCreator noEffect(int status, String scenario) {
    return outbound -> {
      try {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        var codec = new AccountLifecycleCodec("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        var body = codec.read(((MockClientHttpRequest) outbound).getBodyAsString(), com.fasterxml.jackson.databind.JsonNode.class);
        int signedStatus = "invalid-400".equals(scenario) ? 400 : status;
        var payload = new TreeMap<String, Object>();
        payload.put("realmId", REALM);
        payload.put("accountId", ACCOUNT);
        payload.put("operationId", operation);
        payload.put("command", "locale");
        payload.put("ownerClient", "backend-account-admin");
        payload.put("ownerSubject", "33333333-3333-4333-8333-333333333333");
        payload.put("intentDigest", "foreign-intent".equals(scenario) ? "b".repeat(64) : codec.digest("maintenance-intent", body));
        payload.put("previousState", body.path("expectedState").textValue());
        payload.put("tenantId", "7");
        payload.put("originDigest", codec.digest("maintenance-origin", body.path("target").path("origin")));
        boolean updated = "updated".equals(scenario);
        payload.put("result", updated ? "UPDATED" : "NO_EFFECT");
        if (!updated) payload.put("status", signedStatus);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(codec.canonical(payload).getBytes(StandardCharsets.UTF_8));
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String domain = updated ? "maintenance-operation-receipt\n" : "maintenance-no-effect-receipt/v1\n";
        String receipt = encoded + "." + ("wrong-mac".equals(scenario) ? "0".repeat(64) : HexFormat.of().formatHex(mac.doFinal((domain + encoded).getBytes(StandardCharsets.UTF_8))));
        String reply = "unsigned".equals(scenario) ? "{}" : updated ? codec.canonical(Map.of("operationId", operation, "accountId", ACCOUNT, "result", "UPDATED", "receipt", receipt)) : codec.canonical(Map.of("operationId", operation, "accountId", ACCOUNT, "result", "NO_EFFECT", "status", "status-mismatch".equals(scenario) ? 409 : signedStatus, "receipt", receipt));
        return org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(reply).createResponse(outbound);
      } catch (java.io.IOException failure) {
        throw failure;
      } catch (Exception failure) {
        throw new java.io.IOException(failure);
      }
    };
  }

  private void assertMutationHeaders(RuntimeException failure, int status, String key, boolean expected) throws Exception {
    var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new LocaleFailureBoundary(failure))
        .setControllerAdvice(new de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiResponseEntityExceptionHandler()).build();
    var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/locale-outcome")
        .requestAttr("X-ORISO-Mutation-Outcome", "NO_EFFECT").requestAttr("X-ORISO-Mutation-Operation", operation);
    if (key != null) request.header("Idempotency-Key", key);
    var response = mvc.perform(request).andReturn().getResponse();
    assertEquals(status, response.getStatus());
    assertEquals(expected ? "NO_EFFECT" : null, response.getHeader("X-ORISO-Mutation-Outcome"));
    assertEquals(expected ? operation : null, response.getHeader("X-ORISO-Mutation-Operation"));
    assertEquals(expected ? "no-store" : null, response.getHeader("Cache-Control"));
    assertEquals("", response.getContentAsString());
  }

  @org.springframework.web.bind.annotation.RestController
  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  static final class LocaleFailureBoundary {
    private final RuntimeException failure;
    LocaleFailureBoundary(RuntimeException failure) { this.failure = failure; }
    @org.springframework.web.bind.annotation.PatchMapping("/locale-outcome")
    void fail() { throw failure; }
  }
}
