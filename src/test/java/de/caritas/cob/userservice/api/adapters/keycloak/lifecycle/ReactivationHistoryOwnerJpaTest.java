package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.AskerReactivationRequestDTO;
import de.caritas.cob.userservice.api.admin.facade.AskerUserAdminFacade;
import de.caritas.cob.userservice.api.admin.service.*;
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
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/** Real facade/JPA custody with signed HTTP doubles; simulated data-retention time is not native expiry proof. */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReactivationHistoryOwnerJpaTest {
  private static final String ACCOUNT = "85000000-0000-4000-8000-000000000071";
  private static final String HUMAN = "85000000-0000-4000-8000-000000000072";
  private static final String OWNER = "85000000-0000-4000-8000-000000000073";
  private static final String REALM = "85000000-0000-4000-8000-000000000074";
  private static final String ISSUER = "https://identity.test/realms/history";
  private static final String URL = ISSUER + "/oriso-account-maintenance/v1/accounts/" + ACCOUNT;
  private static final String USERNAME = "history-asker", EMAIL = "history@fixture.invalid";
  private static final String PASSWORD = "synthetic-history-chosen-password";
  private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  @Autowired private DataSource source;
  @Autowired private UserRepository users;
  @Autowired private PlatformTransactionManager transactions;
  private final MutableClock clock = new MutableClock();
  private JdbcTemplate jdbc;
  private AccountLifecycleCodec codec;
  private AskerUserAdminFacade facade;
  private IdentityReactivator generic;
  private IdentityReactivationRepairService repair;
  private DeletionLifecycleService lifecycle;
  private AuthenticatedUser authenticated;
  private MockHttpServletRequest request;
  private TaskActorTokenBroker.VerifiedMaintenanceToken machine;
  private MockRestServiceServer server;
  private String nativeUsername;
  private int token;
  private final TaskActorCredentials actor = new TaskActorCredentials(
      TaskActor.ACCOUNT_ADMIN, "backend-account-admin", "synthetic-only", OWNER);

  @BeforeEach
  void actualPrivateOwnerAndCommittedExclusiveSqlUser() {
    clock.current = Instant.now();
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
    codec = new AccountLifecycleCodec(KEY, clock);
    generic = mock(IdentityReactivator.class);
    repair = mock(IdentityReactivationRepairService.class);
    lifecycle = spy(new DeletionLifecycleService());
    var scope = mock(AdminScope.class);
    var saga = new IdentityReactivationSagaStore(users, transcoder, generic, lifecycle);
    facade = new AskerUserAdminFacade(mock(IdentityDeactivator.class), saga, repair,
        mock(UserService.class), transcoder, lifecycle, scope);
    var mode = mock(IdentityClientConfig.class);
    when(mode.isFiveActorMode()).thenReturn(true);
    facade.setIdentityMode(mode);
    authenticated = new AuthenticatedUser(HUMAN, "history-admin", Set.of("single-tenant-admin"),
        "fixture-human", Set.of("ROLE_USER_ADMIN"));
    authenticated.setTenantId(7L);
    request = new MockHttpServletRequest("POST", "/service/useradmin/askers/deletion/reactivate");
    var actors = mock(TaskActorConfiguration.class);
    when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(actor);
    var broker = mock(TaskActorTokenBroker.class);
    machine = mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
    when(machine.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);
    when(machine.purpose()).thenReturn(TaskPurpose.ACCOUNT_MAINTENANCE);
    when(machine.clientId()).thenReturn(actor.clientId());
    when(machine.subject()).thenReturn(OWNER);
    when(machine.issuer()).thenReturn(ISSUER);
    when(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(machine);
    var http = new RestTemplate();
    server = MockRestServiceServer.bindTo(http).build();
    facade.setScopedReactivation(new ScopedAskerReactivation(mode, authenticated, scope, request,
        jdbc, transactions, actors, broker, codec, http, clock, "https://identity.test", ISSUER,
        "history", REALM, saga));
    renewHuman();
  }

  @AfterEach
  void clearScope() { TenantContext.clear(); }

  @Test
  void erasedUnknownResumesOnlyTheOriginalIntentAndAnAuthenticatedTerminalAllowsANewCycle()
      throws Exception {
    expectRead("a".repeat(64));
    var firstBody = new AtomicReference<String>();
    var firstReceipt = new AtomicReference<String>();
    server.expect(requestTo(URL + "/reactivate")).andRespond(outbound -> {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      String body = ((MockClientHttpRequest) outbound).getBodyAsString();
      firstBody.set(body); firstReceipt.set(signed(body));
      assertEquals("FROZEN", raw().path("phase").textValue());
      throw new ResourceAccessException("synthetic lost receiver response");
    });
    assertThrows(de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException.class,
        () -> facade.reactivateAsker(input(PASSWORD)));
    server.verify(); server.reset();
    var frozen = raw();
    var originalSql = codec.read(codec.utf8(codec.openReactivationIntent(
        frozen.path("metadata"), frozen.path("envelope").textValue())), JsonNode.class).path("originalSql");
    long expiry = frozen.path("metadata").path("authorityValidUntil").longValue();
    // Only the local data-retention clock advances. No native/token-expiry credit is claimed.
    clock.current = Instant.ofEpochSecond(expiry + 604800);
    var erased = (ObjectNode) frozen.deepCopy();
    erased.put("phase", "FROZEN_HISTORY");
    erased.put("envelope", codec.retainReactivationHistory(frozen.path("metadata"),
        frozen.path("envelope").textValue(), "FROZEN", null, actor));
    write(erased);
    var held = jdbc.queryForMap("select * from user where user_id=?", ACCOUNT);
    assertEquals(originalSql, ReactivationUserSnapshotSchema.capture(jdbc, codec, ACCOUNT, false));
    verify(lifecycle, never()).cancelUserDeletion(any(User.class));
    renewHuman();
    assertThrows(RuntimeException.class, () -> facade.reactivateAsker(input("unrelated-password")));
    assertEquals(held, jdbc.queryForMap("select * from user where user_id=?", ACCOUNT));
    server.expect(requestTo(URL + "/reactivate")).andRespond(outbound -> {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      assertTrue(firstBody.get().equals(((MockClientHttpRequest) outbound).getBodyAsString()));
      return withSuccess(firstReceipt.get(), MediaType.APPLICATION_JSON).createResponse(outbound);
    });
    assertDoesNotThrow(() -> facade.reactivateAsker(input(PASSWORD)));
    server.verify(); server.reset();
    var acknowledged = raw();
    assertEquals("ACKNOWLEDGED_HISTORY", acknowledged.path("phase").textValue());
    assertEquals(frozen.path("metadata"), acknowledged.path("metadata"));
    assertEquals(firstReceipt.get(), acknowledged.path("receipt").textValue());
    var history = codec.verifyReactivationHistory(acknowledged.path("metadata"),
        acknowledged.path("envelope").textValue(), "ACKNOWLEDGED_HISTORY", firstReceipt.get(), actor);
    assertEquals(originalSql, history.originalSql());
    assertEquals(2, history.template().path("change").size());
    assertFalse(history.template().path("change").has("password"));
    verify(lifecycle, times(1)).cancelUserDeletion(any(User.class));
    var afterAck = jdbc.queryForMap("select * from user where user_id=?", ACCOUNT);
    server.expect(requestTo(URL + "/reactivate")).andRespond(outbound -> {
      assertTrue(firstBody.get().equals(((MockClientHttpRequest) outbound).getBodyAsString()));
      return withSuccess(firstReceipt.get(), MediaType.APPLICATION_JSON).createResponse(outbound);
    });
    assertDoesNotThrow(() -> facade.reactivateAsker(input(PASSWORD)));
    assertEquals(acknowledged, raw());
    assertEquals(afterAck, jdbc.queryForMap("select * from user where user_id=?", ACCOUNT));
    verify(lifecycle, times(1)).cancelUserDeletion(any(User.class));
    server.verify(); server.reset();
    // Return the data-only clock to real time before a genuinely new SQL deletion cycle.
    clock.current = Instant.now(); renewHuman();
    var next = users.findById(ACCOUNT).orElseThrow();
    lifecycle.beginUserDeletion(next, HUMAN); users.save(next);
    expectRead("b".repeat(64));
    server.expect(requestTo(URL + "/reactivate")).andRespond(outbound -> {
      String body = ((MockClientHttpRequest) outbound).getBodyAsString();
      assertNotEquals(frozen.path("metadata").path("operationId"),
          codec.read(body, JsonNode.class).path("operationId"));
      assertTrue("second-synthetic-password".equals(codec.read(body, JsonNode.class).path("change").path("password").textValue()));
      return withSuccess(signed(body), MediaType.APPLICATION_JSON).createResponse(outbound);
    });
    assertDoesNotThrow(() -> facade.reactivateAsker(input("second-synthetic-password")));
    assertEquals("ACKNOWLEDGED", raw().path("phase").textValue());
    assertNotEquals(frozen.path("metadata").path("operationId"), raw().path("metadata").path("operationId"));
    verify(lifecycle, times(2)).cancelUserDeletion(any(User.class));
    verifyNoInteractions(generic, repair);
    server.verify();
  }

  private void renewHuman() {
    String bearer = "fixture-human-" + ++token;
    authenticated.setAccessToken(bearer);
    var jwt = Jwt.withTokenValue(bearer).header("alg", "RS256").subject(HUMAN).issuer(ISSUER)
        .claim("azp", "admin-ui").claim("tenantId", "7").issuedAt(clock.instant().minusSeconds(1))
        .expiresAt(clock.instant().plusSeconds(600)).build();
    request.setUserPrincipal(new JwtAuthenticationToken(jwt, List.of(
        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER_ADMIN"))));
    request.removeHeader("Authorization"); request.addHeader("Authorization", "Bearer " + bearer);
    when(machine.accessToken()).thenReturn("fixture-machine-" + token);
    when(machine.validatedJwtIssuedAt()).thenReturn(clock.instant().minusSeconds(1).getEpochSecond());
    when(machine.validatedJwtExpiresAt()).thenReturn(clock.instant().plusSeconds(600).getEpochSecond());
  }

  private AskerReactivationRequestDTO input(String password) {
    var input = new AskerReactivationRequestDTO();
    input.setUsername(USERNAME);
    input.setEmail(EMAIL);
    input.setTenantId(7L);
    input.setPassword(password);
    return input;
  }

  private JsonNode raw() {
    return codec.read(jdbc.queryForObject("select reactivation_scoped_json from user where user_id=?", String.class, ACCOUNT), JsonNode.class);
  }

  private void write(JsonNode raw) {
    jdbc.update("update user set reactivation_scoped_json=? where user_id=?", codec.canonical(raw), ACCOUNT);
  }

  private void expectRead(String state) {
    var projection = new TreeMap<String, Object>();
    projection.put("id", ACCOUNT); projection.put("username", nativeUsername); projection.put("email", EMAIL);
    projection.put("firstName", null); projection.put("lastName", null); projection.put("enabled", false);
    projection.put("emailVerified", true); projection.put("tenantId", "7"); projection.put("preferredLanguage", null);
    projection.put("effectiveRoles", List.of("user")); projection.put("passwordChangeRequired", false); projection.put("state", state);
    server.expect(requestTo(URL + "/read")).andRespond(withSuccess(codec.canonical(projection), MediaType.APPLICATION_JSON));
  }

  private String signed(String raw) {
    var body = codec.read(raw, JsonNode.class);
    var payload = new TreeMap<String, Object>();
    payload.put("realmId", REALM); payload.put("accountId", ACCOUNT); payload.put("operationId", body.path("operationId").textValue());
    payload.put("command", "reactivate"); payload.put("ownerClient", actor.clientId()); payload.put("ownerSubject", OWNER);
    payload.put("intentDigest", codec.digest("maintenance-intent", body)); payload.put("previousState", body.path("expectedState").textValue());
    payload.put("tenantId", "7"); payload.put("originDigest", codec.digest("maintenance-origin", body.path("target").path("origin"))); payload.put("result", "UPDATED");
    String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(codec.canonical(payload).getBytes(StandardCharsets.UTF_8));
    try {
      var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
      return codec.canonical(Map.of("operationId", body.path("operationId").textValue(), "accountId", ACCOUNT,
          "result", "UPDATED", "receipt", encoded + "." + HexFormat.of().formatHex(mac.doFinal(("maintenance-operation-receipt\n" + encoded).getBytes(StandardCharsets.UTF_8)))));
    } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException("Synthetic receipt unavailable"); }
  }

  private static final class MutableClock extends Clock {
    private Instant current = Instant.now();
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return current; }
  }
}
