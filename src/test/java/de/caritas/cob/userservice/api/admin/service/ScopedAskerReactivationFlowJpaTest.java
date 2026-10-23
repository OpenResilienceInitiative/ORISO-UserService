package de.caritas.cob.userservice.api.admin.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.*;
import de.caritas.cob.userservice.api.adapters.web.dto.AskerReactivationRequestDTO;
import de.caritas.cob.userservice.api.admin.facade.AskerUserAdminFacade;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionLifecycleService;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

/** Actual facade/store/JPA, with a separately signed HTTP double, not native acceptance. */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScopedAskerReactivationFlowJpaTest {
  private static final String ACCOUNT = "85000000-0000-4000-8000-000000000051";
  private static final String HUMAN = "85000000-0000-4000-8000-000000000052";
  private static final String OWNER = "85000000-0000-4000-8000-000000000053";
  private static final String REALM = "85000000-0000-4000-8000-000000000054";
  private static final String USERNAME = "soft-deleted-asker";
  private static final String EMAIL = "MiXeD@fixture.invalid";
  private static final String PASSWORD = "synthetic-chosen-password";
  private static final String ISSUER = "https://identity.test/realms/fixture";
  private static final String URL = ISSUER + "/oriso-account-maintenance/v1/accounts/" + ACCOUNT;
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  @Autowired private DataSource source;
  @Autowired private UserRepository users;
  @Autowired private PlatformTransactionManager transactions;
  private JdbcTemplate jdbc;
  private AccountLifecycleCodec codec;
  private AskerUserAdminFacade facade;
  private MockRestServiceServer server;
  private AdminScope scope;
  private IdentityReactivator generic;
  private IdentityReactivationRepairService repair;
  private DeletionLifecycleService lifecycle;
  private String nativeUsername;
  private final MutableClock clock = new MutableClock(Instant.now());
  private AuthenticatedUser authenticated;
  private MockHttpServletRequest request;
  private TaskActorTokenBroker.VerifiedMaintenanceToken checked;
  private boolean emptyNativeNames;
  private String firstBody;
  private String receipt;
  private final AtomicInteger effects = new AtomicInteger();

  @BeforeEach
  void committedExclusiveAskerAndRealFacade() {
    clock.current = Instant.now();
    assertInstanceOf(JpaTransactionManager.class, transactions);
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    TenantContext.setCurrentTenant(7L);
    jdbc = new JdbcTemplate(source);
    new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.ClassPathResource(
                "db/changelog/changeset/20261023_identity_asker_reactivation_367/migrate.sql"))
        .execute(source);
    users.findById(ACCOUNT).ifPresent(users::delete);
    var transcoder = new UsernameTranscoder();
    nativeUsername = transcoder.encodeUsername(USERNAME).toLowerCase(Locale.ROOT);
    var user = new User(ACCOUNT, null, transcoder.encodeUsername(USERNAME), EMAIL, false);
    user.setTenantId(7L);
    user.setLanguageCode(LanguageCode.en);
    user.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC).minusDays(3));
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    user.setDeletionReadOnlyUntil(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
    users.save(user);
    codec = new AccountLifecycleCodec(KEY);
    generic = mock(IdentityReactivator.class);
    repair = mock(IdentityReactivationRepairService.class);
    scope = mock(AdminScope.class);
    lifecycle = spy(new DeletionLifecycleService());
    var saga = new IdentityReactivationSagaStore(users, transcoder, generic, lifecycle);
    facade =
        new AskerUserAdminFacade(
            mock(IdentityDeactivator.class),
            saga,
            repair,
            mock(UserService.class),
            transcoder,
            lifecycle,
            scope);
    var mode = mock(IdentityClientConfig.class);
    when(mode.isFiveActorMode()).thenReturn(true);
    facade.setIdentityMode(mode);
    authenticated =
        new AuthenticatedUser(
            HUMAN,
            "actual-admin",
            Set.of("single-tenant-admin"),
            "fixture-human",
            Set.of("ROLE_USER_ADMIN"));
    authenticated.setTenantId(7L);
    request =
        new MockHttpServletRequest("POST", "/service/useradmin/askers/deletion/reactivate");
    var now = clock.instant();
    var jwt =
        Jwt.withTokenValue("fixture-human")
            .header("alg", "RS256")
            .subject(HUMAN)
            .issuer(ISSUER)
            .claim("azp", "admin-ui")
            .claim("tenantId", "7")
            .issuedAt(now.minusSeconds(5))
            .expiresAt(now.plusSeconds(600))
            .build();
    request.setUserPrincipal(
        new JwtAuthenticationToken(
            jwt,
            List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    "ROLE_USER_ADMIN"))));
    request.addHeader("Authorization", "Bearer fixture-human");
    var actors = mock(TaskActorConfiguration.class);
    when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE))
        .thenReturn(
            new TaskActorCredentials(
                TaskActor.ACCOUNT_ADMIN, "backend-account-admin", "fixture-secret", OWNER));
    var broker = mock(TaskActorTokenBroker.class);
    checked = mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
    when(checked.accessToken()).thenReturn("fixture-admin");
    when(checked.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);
    when(checked.purpose()).thenReturn(TaskPurpose.ACCOUNT_MAINTENANCE);
    when(checked.clientId()).thenReturn("backend-account-admin");
    when(checked.subject()).thenReturn(OWNER);
    when(checked.issuer()).thenReturn(ISSUER);
    when(checked.validatedJwtIssuedAt()).thenReturn(now.minusSeconds(1).getEpochSecond());
    when(checked.validatedJwtExpiresAt()).thenReturn(now.plusSeconds(600).getEpochSecond());
    when(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(checked);
    var http = new RestTemplate();
    server = MockRestServiceServer.bindTo(http).build();
    facade.setScopedReactivation(
        new ScopedAskerReactivation(
            mode,
            authenticated,
            scope,
            request,
            jdbc,
            transactions,
            actors,
            broker,
            codec,
            http,
            clock,
            "https://identity.test",
            ISSUER,
            "fixture",
            REALM,
            saga));
  }

  @AfterEach
  void clearTenant() {
    for (String extra :
        List.of("85000000-0000-4000-8000-000000000061", "85000000-0000-4000-8000-000000000062"))
      users.findById(extra).ifPresent(users::delete);
    TenantContext.clear();
  }

  @Test
  void chosenPasswordIsCipherOnlyBeforeNativeAndAcknowledgedBeforeOneLocalCancelWithExactReplay() {
    completeAndReplay();
  }

  @Test
  void historicalDuplicateUsernamesStillSelectTheOneExactEmailAndTenantIncarnation() {
    for (int index = 1; index <= 2; index++) {
      String id = "85000000-0000-4000-8000-00000000006" + index;
      var duplicate =
          new User(
              id,
              null,
              new UsernameTranscoder().encodeUsername(USERNAME),
              "unrelated-" + index + "@fixture.invalid",
              false);
      duplicate.setTenantId(7L);
      duplicate.setLanguageCode(LanguageCode.en);
      duplicate.setDeleteDate(LocalDateTime.now(ZoneOffset.UTC).minusDays(3));
      duplicate.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
      users.save(duplicate);
    }
    completeAndReplay();
  }

  @Test
  void legacyAskerWithoutNativeDisplayNamesStillUsesTheExactUsernameEmailTenantAndState() {
    emptyNativeNames = true;
    completeAndReplay();
  }

  private void completeAndReplay() {
    server
        .expect(requestTo(URL + "/read"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            outbound -> {
              assertOutsideSql();
              assertNull(
                  jdbc.queryForObject(
                      "select reactivation_operation_id from user where user_id=?",
                      String.class,
                      ACCOUNT));
              return withSuccess(
                      codec.canonical(
                          Map.ofEntries(
                              Map.entry("id", ACCOUNT),
                              Map.entry("username", nativeUsername),
                              Map.entry("email", EMAIL),
                              Map.entry("firstName", emptyNativeNames ? "" : "Actual"),
                              Map.entry("lastName", emptyNativeNames ? "" : "Asker"),
                              Map.entry("enabled", false),
                              Map.entry("emailVerified", true),
                              Map.entry("tenantId", "7"),
                              Map.entry("preferredLanguage", "en"),
                              Map.entry("effectiveRoles", List.of("user")),
                              Map.entry("passwordChangeRequired", false),
                              Map.entry("state", "a".repeat(64)))),
                      MediaType.APPLICATION_JSON)
                  .createResponse(outbound);
            });
    for (int call = 0; call < 2; call++)
      server
          .expect(requestTo(URL + "/reactivate"))
          .andExpect(method(HttpMethod.POST))
          .andRespond(
              outbound -> {
                assertOutsideSql();
                var body = ((MockClientHttpRequest) outbound).getBodyAsString();
                if (firstBody == null) {
                  firstBody = body;
                  var saved =
                      jdbc.queryForObject(
                          "select reactivation_scoped_json from user where user_id=?",
                          String.class,
                          ACCOUNT);
                  assertNotNull(saved);
                  assertFalse(saved.contains(PASSWORD));
                  assertFalse(saved.contains("fixture-human"));
                  assertEquals(
                      "FROZEN", codec.read(saved, JsonNode.class).path("phase").textValue());
                  assertNotNull(
                      jdbc.queryForObject(
                          "select delete_date from user where user_id=?",
                          java.sql.Timestamp.class,
                          ACCOUNT));
                  assertEquals(
                      "REACTIVATION_IN_PROGRESS",
                      jdbc.queryForObject(
                          "select deletion_lifecycle_state from user where user_id=?",
                          String.class,
                          ACCOUNT));
                  effects.incrementAndGet();
                  receipt = signed(body);
                } else
                  assertEquals(
                      firstBody, body, "Replay must use the original native bytes and operation");
                return withSuccess(receipt, MediaType.APPLICATION_JSON).createResponse(outbound);
              });
    assertDoesNotThrow(() -> facade.reactivateAsker(input()));
    assertEquals(
        "ACTIVE",
        jdbc.queryForObject(
            "select deletion_lifecycle_state from user where user_id=?", String.class, ACCOUNT));
    assertNull(
        jdbc.queryForObject(
            "select delete_date from user where user_id=?", java.sql.Timestamp.class, ACCOUNT));
    assertNull(
        jdbc.queryForObject(
            "select reactivation_operation_id from user where user_id=?", String.class, ACCOUNT));
    var acknowledged =
        jdbc.queryForObject(
            "select reactivation_scoped_json from user where user_id=?", String.class, ACCOUNT);
    assertEquals(
        "ACKNOWLEDGED", codec.read(acknowledged, JsonNode.class).path("phase").textValue());
    assertDoesNotThrow(() -> facade.reactivateAsker(input()));
    assertEquals(
        acknowledged,
        jdbc.queryForObject(
            "select reactivation_scoped_json from user where user_id=?", String.class, ACCOUNT));
    assertEquals(1, effects.get());
    verify(scope, atLeast(2)).assertMay(AdminScope.Target.adviceSeeker(ACCOUNT));
    verifyNoInteractions(generic, repair);
    verify(lifecycle, times(1)).cancelUserDeletion(any(User.class));
    server.verify();
  }

  @Test
  void signedPolicyRejectionReleasesOnlyItsClaimAndCorrectedPasswordGetsANewOperation() {
    expectRead();
    var refusedBody = new java.util.concurrent.atomic.AtomicReference<String>();
    for (int retry = 0; retry < 2; retry++)
      server
          .expect(requestTo(URL + "/reactivate"))
          .andRespond(
              outbound -> {
                assertOutsideSql();
                String raw = ((MockClientHttpRequest) outbound).getBodyAsString();
                if (refusedBody.get() == null) refusedBody.set(raw);
                else
                  assertTrue(
                      refusedBody.get().equals(raw),
                      "Exact rejected request must retain original bytes");
                return org.springframework.test.web.client.response.MockRestResponseCreators
                    .withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(signed(raw, 400))
                    .createResponse(outbound);
              });
    expectRead();
    server
        .expect(requestTo(URL + "/reactivate"))
        .andRespond(
            outbound -> {
              assertOutsideSql();
              String raw = ((MockClientHttpRequest) outbound).getBodyAsString();
              var body = codec.read(raw, JsonNode.class);
              assertNotEquals(
                  codec.read(refusedBody.get(), JsonNode.class).path("operationId"),
                  body.path("operationId"));
              assertEquals(PASSWORD, body.path("change").path("password").textValue());
              return withSuccess(signed(raw), MediaType.APPLICATION_JSON).createResponse(outbound);
            });
    var rejected = input();
    rejected.setPassword("weak");
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException.class,
        () -> facade.reactivateAsker(rejected));
    var original =
        codec.read(
            jdbc.queryForObject(
                "select reactivation_scoped_json from user where user_id=?", String.class, ACCOUNT),
            JsonNode.class);
    assertEquals("NO_EFFECT", original.path("phase").textValue());
    assertNull(
        jdbc.queryForObject(
            "select reactivation_operation_id from user where user_id=?", String.class, ACCOUNT));
    assertNotNull(
        jdbc.queryForObject(
            "select delete_date from user where user_id=?", java.sql.Timestamp.class, ACCOUNT));
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException.class,
        () -> facade.reactivateAsker(rejected));
    assertEquals(
        codec.canonical(original),
        jdbc.queryForObject(
            "select reactivation_scoped_json from user where user_id=?", String.class, ACCOUNT));
    assertDoesNotThrow(() -> facade.reactivateAsker(input()));
    assertEquals(
        "ACTIVE",
        jdbc.queryForObject(
            "select deletion_lifecycle_state from user where user_id=?", String.class, ACCOUNT));
    verify(lifecycle, times(1)).cancelUserDeletion(any(User.class));
    verifyNoInteractions(generic, repair);
    server.verify();
  }

  @Test
  void unsignedRefusalKeepsCipherAndClaimAndRetriesOnlyTheOriginalCommand() {
    expectRead();
    var originalBody = new java.util.concurrent.atomic.AtomicReference<String>();
    server
        .expect(requestTo(URL + "/reactivate"))
        .andRespond(
            outbound -> {
              assertOutsideSql();
              originalBody.set(((MockClientHttpRequest) outbound).getBodyAsString());
              return org.springframework.test.web.client.response.MockRestResponseCreators
                  .withStatus(org.springframework.http.HttpStatus.CONFLICT)
                  .contentType(MediaType.APPLICATION_JSON)
                  .body("{}")
                  .createResponse(outbound);
            });
    server
        .expect(requestTo(URL + "/reactivate"))
        .andRespond(
            outbound -> {
              assertOutsideSql();
              String raw = ((MockClientHttpRequest) outbound).getBodyAsString();
              assertTrue(
                  originalBody.get().equals(raw),
                  "Unknown outcome cannot rebind native body or operation");
              return withSuccess(signed(raw), MediaType.APPLICATION_JSON).createResponse(outbound);
            });
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException.class,
        () -> facade.reactivateAsker(input()));
    String frozen =
        jdbc.queryForObject(
            "select reactivation_scoped_json from user where user_id=?", String.class, ACCOUNT);
    assertEquals("FROZEN", codec.read(frozen, JsonNode.class).path("phase").textValue());
    assertNotNull(
        jdbc.queryForObject(
            "select reactivation_operation_id from user where user_id=?", String.class, ACCOUNT));
    verify(lifecycle, never()).cancelUserDeletion(any(User.class));
    assertDoesNotThrow(() -> facade.reactivateAsker(input()));
    var acknowledged =
        codec.read(
            jdbc.queryForObject(
                "select reactivation_scoped_json from user where user_id=?", String.class, ACCOUNT),
            JsonNode.class);
    assertEquals(
        codec.read(frozen, JsonNode.class).path("envelope"), acknowledged.path("envelope"));
    assertEquals(
        codec.read(frozen, JsonNode.class).path("metadata"), acknowledged.path("metadata"));
    verify(lifecycle, times(1)).cancelUserDeletion(any(User.class));
    verifyNoInteractions(generic, repair);
    server.verify();
  }

  @Test
  void terminalPhaseReceiptAndPostSqlTamperingCannotAuthorizeReplayOrAnotherCallback() {
    completeAndReplay();
    server.reset();
    var original =
        codec.read(
            jdbc.queryForObject(
                "select reactivation_scoped_json from user where user_id=?", String.class, ACCOUNT),
            JsonNode.class);
    var row =
        jdbc.queryForMap(
            "select user_id,username,email,create_date,update_date,delete_date,deletion_lifecycle_state,reactivation_operation_id from user where user_id=?",
            ACCOUNT);
    for (String scenario :
        List.of(
            "phase",
            "receipt",
            "post-birth",
            "post-email",
            "post-language",
            "metadata-owner",
            "extra")) {
      var changed = original.deepCopy();
      var object = (com.fasterxml.jackson.databind.node.ObjectNode) changed;
      switch (scenario) {
        case "phase" -> object.put("phase", "FROZEN");
        case "receipt" -> object.put("receipt", "{}");
        case "post-birth" ->
            ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("postSql"))
                .put("create_date", "2026-01-01T00:00:00Z");
        case "post-email" ->
            ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("postSql"))
                .put("email", "unrelated@fixture.invalid");
        case "post-language" ->
            ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("postSql"))
                .put("language_code", "de");
        case "metadata-owner" ->
            ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("metadata"))
                .put("ownerSubject", HUMAN);
        case "extra" -> object.put("callerAuthority", true);
      }
      jdbc.update(
          "update user set reactivation_scoped_json=? where user_id=?",
          codec.canonical(changed),
          ACCOUNT);
      assertThrows(RuntimeException.class, () -> facade.reactivateAsker(input()), scenario);
      assertEquals(
          row,
          jdbc.queryForMap(
              "select user_id,username,email,create_date,update_date,delete_date,deletion_lifecycle_state,reactivation_operation_id from user where user_id=?",
              ACCOUNT));
      assertEquals(
          codec.canonical(changed),
          jdbc.queryForObject(
              "select reactivation_scoped_json from user where user_id=?", String.class, ACCOUNT));
    }
    verify(lifecycle, times(1)).cancelUserDeletion(any(User.class));
    verifyNoInteractions(generic, repair);
    server.verify();
  }


  @Test
  void signedForbiddenAndConflictReleaseOnlyTheirExactFencedClaim() {
    for (int status : List.of(403,409)) {
      expectRead();
      for (int replay=0;replay<2;replay++) server.expect(requestTo(URL + "/reactivate")).andRespond(outbound -> {
        assertOutsideSql();String raw=((MockClientHttpRequest)outbound).getBodyAsString();
        return org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.valueOf(status))
            .contentType(MediaType.APPLICATION_JSON).body(signed(raw,status)).createResponse(outbound);
      });
    }
    for (int status : List.of(403,409)) {
      var chosen=input();chosen.setPassword("distinct-refused-password-"+status);
      Class<? extends RuntimeException> type=status==403?de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class:de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class;
      assertThrows(type,()->facade.reactivateAsker(chosen));
      var saved=jdbc.queryForObject("select reactivation_scoped_json from user where user_id=?",String.class,ACCOUNT);
      assertEquals("NO_EFFECT",codec.read(saved,JsonNode.class).path("phase").textValue());
      assertNull(jdbc.queryForObject("select reactivation_operation_id from user where user_id=?",String.class,ACCOUNT));
      assertNotNull(jdbc.queryForObject("select delete_date from user where user_id=?",java.sql.Timestamp.class,ACCOUNT));
      assertThrows(type,()->facade.reactivateAsker(chosen));
      assertEquals(saved,jdbc.queryForObject("select reactivation_scoped_json from user where user_id=?",String.class,ACCOUNT));
    }
    verify(lifecycle,never()).cancelUserDeletion(any(User.class));verifyNoInteractions(generic,repair);server.verify();
  }

  @Test
  void expiredOriginalBearerCannotRetryWhileFreshSameHumanRenewsOnlyTheOriginalFrozenCommand() {
    expectRead();
    var originalBody=new java.util.concurrent.atomic.AtomicReference<String>();
    server.expect(requestTo(URL+"/reactivate")).andRespond(outbound->{
      originalBody.set(((MockClientHttpRequest)outbound).getBodyAsString());
      return org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.CONFLICT).body("{}").createResponse(outbound);
    });
    server.expect(requestTo(URL+"/reactivate")).andRespond(outbound->{
      assertOutsideSql();String raw=((MockClientHttpRequest)outbound).getBodyAsString();assertTrue(originalBody.get().equals(raw),"Renewal cannot change frozen command");
      return withSuccess(signed(raw),MediaType.APPLICATION_JSON).createResponse(outbound);
    });
    assertThrows(de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException.class,()->facade.reactivateAsker(input()));
    var frozen=codec.read(jdbc.queryForObject("select reactivation_scoped_json from user where user_id=?",String.class,ACCOUNT),JsonNode.class);
    clock.current=Instant.ofEpochSecond(frozen.path("metadata").path("authorityValidUntil").longValue()+1);
    assertThrows(de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,()->facade.reactivateAsker(input()));
    renewHuman("renewed-synthetic-human",HUMAN);
    when(checked.validatedJwtIssuedAt()).thenReturn(clock.instant().minusSeconds(1).getEpochSecond());
    when(checked.validatedJwtExpiresAt()).thenReturn(clock.instant().plusSeconds(600).getEpochSecond());
    assertDoesNotThrow(()->facade.reactivateAsker(input()));
    var acknowledged=codec.read(jdbc.queryForObject("select reactivation_scoped_json from user where user_id=?",String.class,ACCOUNT),JsonNode.class);
    assertEquals(frozen.path("metadata"),acknowledged.path("metadata"));assertEquals(frozen.path("envelope"),acknowledged.path("envelope"));
    verify(lifecycle,times(1)).cancelUserDeletion(any(User.class));server.verify();
  }

  @Test
  void frozenCurrentSqlHumanAndPasswordDriftCannotRebindOrDispatchAnotherCommand() {
    expectRead();
    server.expect(requestTo(URL+"/reactivate")).andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.CONFLICT).body("{}"));
    assertThrows(de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException.class,()->facade.reactivateAsker(input()));server.verify();server.reset();
    var frozen=jdbc.queryForObject("select reactivation_scoped_json from user where user_id=?",String.class,ACCOUNT);
    var row=jdbc.queryForMap("select * from user where user_id=?",ACCOUNT);
    Map<String,Object> changes=new LinkedHashMap<>();changes.put("create_date",java.sql.Timestamp.valueOf("2026-01-01 00:00:00"));changes.put("email","unrelated@fixture.invalid");changes.put("username","unrelated");changes.put("language_code","de");changes.put("notifications_enabled",true);changes.put("avatar_id","different-avatar");changes.put("reactivation_operation_id","85000000-0000-4000-8000-000000000099");
    for(var change:changes.entrySet()) {
      // The only interpolated identifier comes from this private fixed literal map.
      jdbc.update("update user set "+change.getKey()+"=? where user_id=?",change.getValue(),ACCOUNT);
      assertThrows(RuntimeException.class,()->facade.reactivateAsker(input()),change.getKey());
      assertEquals(frozen,jdbc.queryForObject("select reactivation_scoped_json from user where user_id=?",String.class,ACCOUNT));
      jdbc.update("update user set "+change.getKey()+"=? where user_id=?",row.get(change.getKey()),ACCOUNT);
    }
    var changed=input();changed.setPassword("unrelated-new-password");assertThrows(de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class,()->facade.reactivateAsker(changed));
    renewHuman("foreign-synthetic-human","85000000-0000-4000-8000-000000000098");
    assertThrows(RuntimeException.class,()->facade.reactivateAsker(input()));
    assertEquals(row,jdbc.queryForMap("select * from user where user_id=?",ACCOUNT));
    assertEquals(frozen,jdbc.queryForObject("select reactivation_scoped_json from user where user_id=?",String.class,ACCOUNT));
    verify(lifecycle,never()).cancelUserDeletion(any(User.class));verifyNoInteractions(generic,repair);server.verify();
  }

  private void renewHuman(String token,String subject) {
    authenticated.setUserId(subject);authenticated.setAccessToken(token);
    var jwt=Jwt.withTokenValue(token).header("alg","RS256").subject(subject).issuer(ISSUER).claim("azp","admin-ui").claim("tenantId","7").issuedAt(clock.instant().minusSeconds(1)).expiresAt(clock.instant().plusSeconds(600)).build();
    request.setUserPrincipal(new JwtAuthenticationToken(jwt,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER_ADMIN"))));
    request.removeHeader("Authorization");request.addHeader("Authorization","Bearer "+token);
  }

  private static final class MutableClock extends Clock {
    private Instant current;private MutableClock(Instant initial){current=initial;}
    @Override public Instant instant(){return current;}
    @Override public java.time.ZoneId getZone(){return ZoneOffset.UTC;}
    @Override public Clock withZone(java.time.ZoneId zone){if(!ZoneOffset.UTC.equals(zone))throw new IllegalArgumentException("UTC fixture only");return this;}
  }

  private void expectRead() {
    server
        .expect(requestTo(URL + "/read"))
        .andRespond(
            outbound -> {
              assertOutsideSql();
              return withSuccess(
                      codec.canonical(
                          Map.ofEntries(
                              Map.entry("id", ACCOUNT),
                              Map.entry("username", nativeUsername),
                              Map.entry("email", EMAIL),
                              Map.entry("firstName", emptyNativeNames ? "" : "Actual"),
                              Map.entry("lastName", emptyNativeNames ? "" : "Asker"),
                              Map.entry("enabled", false),
                              Map.entry("emailVerified", true),
                              Map.entry("tenantId", "7"),
                              Map.entry("preferredLanguage", "en"),
                              Map.entry("effectiveRoles", List.of("user")),
                              Map.entry("passwordChangeRequired", false),
                              Map.entry("state", "a".repeat(64)))),
                      MediaType.APPLICATION_JSON)
                  .createResponse(outbound);
            });
  }

  private AskerReactivationRequestDTO input() {
    var input = new AskerReactivationRequestDTO();
    input.setUsername(USERNAME);
    input.setEmail(EMAIL);
    input.setTenantId(7L);
    input.setPassword(PASSWORD);
    return input;
  }

  private void assertOutsideSql() {
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    assertFalse(TransactionSynchronizationManager.hasResource(source));
  }

  private String signed(String raw) {
    return signed(raw, null);
  }

  private String signed(String raw, Integer status) {
    try {
      var body = codec.read(raw, JsonNode.class);
      var payload = new TreeMap<String, Object>();
      payload.put("realmId", REALM);
      payload.put("accountId", ACCOUNT);
      payload.put("operationId", body.path("operationId").textValue());
      payload.put("command", "reactivate");
      payload.put("ownerClient", "backend-account-admin");
      payload.put("ownerSubject", OWNER);
      payload.put("intentDigest", codec.digest("maintenance-intent", body));
      payload.put("previousState", body.path("expectedState").textValue());
      payload.put("tenantId", "7");
      payload.put(
          "originDigest", codec.digest("maintenance-origin", body.path("target").path("origin")));
      payload.put("result", status == null ? "UPDATED" : "NO_EFFECT");
      if (status != null) payload.put("status", status);
      var encoded =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(codec.canonical(payload).getBytes(StandardCharsets.UTF_8));
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
      var outer = new TreeMap<String, Object>();
      outer.put("operationId", body.path("operationId").textValue());
      outer.put("accountId", ACCOUNT);
      outer.put("result", status == null ? "UPDATED" : "NO_EFFECT");
      if (status != null) outer.put("status", status);
      outer.put(
          "receipt",
          encoded
              + "."
              + HexFormat.of()
                  .formatHex(
                      mac.doFinal(
                          ((status == null
                                      ? "maintenance-operation-receipt\n"
                                      : "maintenance-no-effect-receipt/v1\n")
                                  + encoded)
                              .getBytes(StandardCharsets.UTF_8))));
      return codec.canonical(outer);
    } catch (Exception invalid) {
      throw new AssertionError(invalid);
    }
  }
}
