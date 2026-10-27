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

/**
 * Real facade/JPA custody with signed HTTP doubles; simulated data-retention time is not native
 * expiry proof.
 */
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
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  @Autowired private DataSource source;
  @Autowired private UserRepository users;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private ScheduledTaskClaimRepository claims;
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
  private ScopedAskerReactivation owner;
  private java.util.function.Supplier<ScopedAskerReactivation> coldOwner;
  private TaskActorTokenBroker broker;
  private final List<String> poisonAccounts = new ArrayList<>();
  private String nativeUsername;
  private int token;
  private final TaskActorCredentials actor =
      new TaskActorCredentials(
          TaskActor.ACCOUNT_ADMIN, "backend-account-admin", "synthetic-only", OWNER);

  @BeforeEach
  void actualPrivateOwnerAndCommittedExclusiveSqlUser() {
    clock.current = Instant.now();
    TenantContext.setCurrentTenant(7L);
    jdbc = new JdbcTemplate(source);
    claims.deleteById(ScopedAskerReactivation.SECRET_ERASURE_TASK);
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
            "history-admin",
            Set.of("single-tenant-admin"),
            "fixture-human",
            Set.of("ROLE_USER_ADMIN"));
    authenticated.setTenantId(7L);
    request = new MockHttpServletRequest("POST", "/service/useradmin/askers/deletion/reactivate");
    var actors = mock(TaskActorConfiguration.class);
    when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(actor);
    broker = mock(TaskActorTokenBroker.class);
    machine = mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
    when(machine.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);
    when(machine.purpose()).thenReturn(TaskPurpose.ACCOUNT_MAINTENANCE);
    when(machine.clientId()).thenReturn(actor.clientId());
    when(machine.subject()).thenReturn(OWNER);
    when(machine.issuer()).thenReturn(ISSUER);
    when(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(machine);
    var http = new RestTemplate();
    server = MockRestServiceServer.bindTo(http).build();
    coldOwner = () -> new ScopedAskerReactivation(
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
            "history",
            REALM,
            saga);
    owner = coldOwner.get();
    facade.setScopedReactivation(owner);
    renewHuman();
  }

  @AfterEach
  void clearScope() {
    for (String account : poisonAccounts) jdbc.update("delete from user where user_id=?", account);
    claims.deleteById(ScopedAskerReactivation.SECRET_ERASURE_TASK);
    TenantContext.clear();
  }

  @Test
  void erasedUnknownResumesOnlyTheOriginalIntentAndAnAuthenticatedTerminalAllowsANewCycle()
      throws Exception {
    expectRead("a".repeat(64));
    var firstBody = new AtomicReference<String>();
    var firstReceipt = new AtomicReference<String>();
    server
        .expect(requestTo(URL + "/reactivate"))
        .andRespond(
            outbound -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              String body = ((MockClientHttpRequest) outbound).getBodyAsString();
              firstBody.set(body);
              firstReceipt.set(signed(body));
              assertEquals("FROZEN", raw().path("phase").textValue());
              throw new ResourceAccessException("synthetic lost receiver response");
            });
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException.class,
        () -> facade.reactivateAsker(input(PASSWORD)));
    server.verify();
    server.reset();
    var frozen = raw();
    var originalSql =
        codec
            .read(
                codec.utf8(
                    codec.openReactivationIntent(
                        frozen.path("metadata"), frozen.path("envelope").textValue())),
                JsonNode.class)
            .path("originalSql");
    long expiry = frozen.path("metadata").path("authorityValidUntil").longValue();
    // Only the local data-retention clock advances. No native/token-expiry credit is claimed.
    clock.current = Instant.ofEpochSecond(expiry + 604800);
    var erased = (ObjectNode) frozen.deepCopy();
    erased.put("phase", "FROZEN_HISTORY");
    erased.put(
        "envelope",
        codec.retainReactivationHistory(
            frozen.path("metadata"), frozen.path("envelope").textValue(), "FROZEN", null, actor));
    write(erased);
    var held = jdbc.queryForMap("select * from user where user_id=?", ACCOUNT);
    assertEquals(originalSql, ReactivationUserSnapshotSchema.capture(jdbc, codec, ACCOUNT, false));
    verify(lifecycle, never()).cancelUserDeletion(any(User.class));
    renewHuman();
    assertThrows(RuntimeException.class, () -> facade.reactivateAsker(input("unrelated-password")));
    assertEquals(held, jdbc.queryForMap("select * from user where user_id=?", ACCOUNT));
    server
        .expect(requestTo(URL + "/reactivate"))
        .andRespond(
            outbound -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertTrue(
                  firstBody.get().equals(((MockClientHttpRequest) outbound).getBodyAsString()));
              return withSuccess(firstReceipt.get(), MediaType.APPLICATION_JSON)
                  .createResponse(outbound);
            });
    assertDoesNotThrow(() -> facade.reactivateAsker(input(PASSWORD)));
    server.verify();
    server.reset();
    var acknowledged = raw();
    assertEquals("ACKNOWLEDGED_HISTORY", acknowledged.path("phase").textValue());
    assertEquals(frozen.path("metadata"), acknowledged.path("metadata"));
    assertEquals(firstReceipt.get(), acknowledged.path("receipt").textValue());
    var history =
        codec.verifyReactivationHistory(
            acknowledged.path("metadata"),
            acknowledged.path("envelope").textValue(),
            "ACKNOWLEDGED_HISTORY",
            firstReceipt.get(),
            actor);
    assertEquals(originalSql, history.originalSql());
    assertEquals(2, history.template().path("change").size());
    assertFalse(history.template().path("change").has("password"));
    verify(lifecycle, times(1)).cancelUserDeletion(any(User.class));
    var afterAck = jdbc.queryForMap("select * from user where user_id=?", ACCOUNT);
    var foreignBody = codec.read(firstBody.get(), ObjectNode.class);
    String foreignOperation = UUID.randomUUID().toString();
    foreignBody.put("operationId", foreignOperation);
    ((ObjectNode) foreignBody.path("target").path("origin")).put("workflowId", foreignOperation);
    String foreignReceipt = signed(codec.canonical(foreignBody));
    for (java.util.function.Consumer<ObjectNode> corrupt :
        List.<java.util.function.Consumer<ObjectNode>>of(
            raw -> raw.put("phase", "ACKNOWLEDGED"),
            raw -> raw.put("phase", "NO_EFFECT_HISTORY"),
            raw -> raw.put("receipt", foreignReceipt),
            raw -> ((ObjectNode) raw.path("postSql")).put("create_date", "2001-01-01T00:00:00Z"),
            raw -> ((ObjectNode) raw.path("postSql")).put("avatar_id", "foreign-avatar"),
            raw -> raw.remove("receipt"),
            raw -> raw.put("extra", true))) {
      var tampered = (ObjectNode) acknowledged.deepCopy();
      corrupt.accept(tampered);
      write(tampered);
      assertThrows(RuntimeException.class, () -> facade.reactivateAsker(input(PASSWORD)));
      assertEquals(tampered, raw());
      verify(lifecycle, times(1)).cancelUserDeletion(any(User.class));
    }
    write(acknowledged);
    assertEquals(afterAck, jdbc.queryForMap("select * from user where user_id=?", ACCOUNT));
    server
        .expect(requestTo(URL + "/reactivate"))
        .andRespond(
            outbound -> {
              assertTrue(
                  firstBody.get().equals(((MockClientHttpRequest) outbound).getBodyAsString()));
              return withSuccess(firstReceipt.get(), MediaType.APPLICATION_JSON)
                  .createResponse(outbound);
            });
    assertDoesNotThrow(() -> facade.reactivateAsker(input(PASSWORD)));
    assertEquals(acknowledged, raw());
    assertEquals(afterAck, jdbc.queryForMap("select * from user where user_id=?", ACCOUNT));
    verify(lifecycle, times(1)).cancelUserDeletion(any(User.class));
    server.verify();
    server.reset();
    // Return the data-only clock to real time before a genuinely new SQL deletion cycle.
    clock.current = Instant.now();
    renewHuman();
    var next = users.findById(ACCOUNT).orElseThrow();
    lifecycle.beginUserDeletion(next, HUMAN);
    users.save(next);
    expectRead("b".repeat(64));
    server
        .expect(requestTo(URL + "/reactivate"))
        .andRespond(
            outbound -> {
              String body = ((MockClientHttpRequest) outbound).getBodyAsString();
              assertNotEquals(
                  frozen.path("metadata").path("operationId"),
                  codec.read(body, JsonNode.class).path("operationId"));
              assertTrue(
                  "second-synthetic-password"
                      .equals(
                          codec
                              .read(body, JsonNode.class)
                              .path("change")
                              .path("password")
                              .textValue()));
              return withSuccess(signed(body), MediaType.APPLICATION_JSON).createResponse(outbound);
            });
    assertDoesNotThrow(() -> facade.reactivateAsker(input("second-synthetic-password")));
    assertEquals("ACKNOWLEDGED", raw().path("phase").textValue());
    assertNotEquals(
        frozen.path("metadata").path("operationId"), raw().path("metadata").path("operationId"));
    verify(lifecycle, times(2)).cancelUserDeletion(any(User.class));
    verifyNoInteractions(generic, repair);
    server.verify();
  }

  @Test
  void erasedPolicyRejectionReleasesOnlyItsClaimAndAllowsADifferentCorrectedPassword() {
    historicalRejection(400);
  }

  @Test
  void localErasureAtTheOriginalDeadlineRetainsTheUnknownClaimWithoutAnyHumanOrRpc() {
    var frozen = uncertain();
    long expiry = frozen.path("metadata").path("authorityValidUntil").longValue();
    var full29 = ReactivationUserSnapshotSchema.capture(jdbc, codec, ACCOUNT, false);
    clock.current = Instant.ofEpochSecond(expiry + 604799);
    var lease = claimErasure();
    removeHuman();
    assertEquals(0, owner.eraseExpiredScopedSecrets(lease));
    assertEquals(frozen, raw());
    clock.current = clock.current.plusSeconds(1);
    assertEquals(1, owner.eraseExpiredScopedSecrets(lease));
    var history = raw();
    assertEquals("FROZEN_HISTORY", history.path("phase").textValue());
    assertEquals(frozen.path("metadata"), history.path("metadata"));
    assertEquals(full29, ReactivationUserSnapshotSchema.capture(jdbc, codec, ACCOUNT, false));
    assertNull(history.path("receipt").textValue());
    assertTrue(history.path("postSql").isNull());
    var verified = codec.verifyReactivationHistory(history.path("metadata"),
        history.path("envelope").textValue(), "FROZEN_HISTORY", null, actor);
    assertFalse(verified.template().path("change").has("password"));
    var stale = new de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService.ClaimLease(
        lease.taskName(), lease.claimedUntil().minusNanos(1000));
    assertEquals(0, owner.eraseExpiredScopedSecrets(stale));
    assertEquals(0, owner.eraseExpiredScopedSecrets(lease));
    assertEquals(history, raw());
    verifyNoInteractions(broker, generic, repair);
    verify(lifecycle, never()).cancelUserDeletion(any(User.class));
    server.verify();
  }

  @Test
  void aColdOwnerContinuesPastMoreThanOneHundredUntouchedPoisonRowsAndEventuallyWraps() {
    var frozen = uncertain();
    var poison = new TreeMap<String, String>();
    for (int index = 0; index < 105; index++) {
      String id = String.format(Locale.ROOT, "10000000-0000-4000-8000-%012d", index);
      var user = new User(id, null, "poison-" + index, EMAIL, false);
      user.setTenantId(7L); user.setLanguageCode(LanguageCode.en);
      users.save(user); poisonAccounts.add(id);
      String raw = index == 0 ? "x".repeat(131073) : "{\"incomplete\":";
      poison.put(id, raw);
      jdbc.update("update user set reactivation_scoped_json=? where user_id=?", raw, id);
    }
    clock.current = Instant.ofEpochSecond(frozen.path("metadata").path("authorityValidUntil").longValue() + 604800);
    var lease = claimErasure();
    removeHuman();
    var full29 = ReactivationUserSnapshotSchema.capture(jdbc, codec, ACCOUNT, false);
    assertEquals(0, owner.eraseExpiredScopedSecrets(lease));
    assertEquals(poisonAccounts.get(99), cursor());
    assertEquals(frozen, raw());
    assertEquals(1, coldOwner.get().eraseExpiredScopedSecrets(lease));
    assertEquals(ACCOUNT, cursor());
    assertEquals("FROZEN_HISTORY", raw().path("phase").textValue());
    assertEquals(full29, ReactivationUserSnapshotSchema.capture(jdbc, codec, ACCOUNT, false));
    for (var row : poison.entrySet()) assertTrue(row.getValue().equals(jdbc.queryForObject(
        "select reactivation_scoped_json from user where user_id=?", String.class, row.getKey())));
    assertEquals(0, coldOwner.get().eraseExpiredScopedSecrets(lease));
    assertNull(cursor());
    verifyNoInteractions(broker, generic, repair);
    verify(lifecycle, never()).cancelUserDeletion(any(User.class));
    server.verify();
  }

  private JsonNode uncertain() {
    expectRead("a".repeat(64));
    server.expect(requestTo(URL + "/reactivate")).andRespond(outbound -> {
      throw new ResourceAccessException("synthetic unknown receiver outcome");
    });
    assertThrows(de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException.class,
        () -> facade.reactivateAsker(input(PASSWORD)));
    server.verify(); server.reset();
    return raw();
  }

  private de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService.ClaimLease claimErasure() {
    var writer = new de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimWriter(claims, clock);
    var service = new de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService(writer);
    // The actual writer/repository run under this physical owning JPA transaction.
    return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(
        ignored -> service.tryClaimLease(ScopedAskerReactivation.SECRET_ERASURE_TASK, Duration.ofMinutes(5)).orElseThrow());
  }

  private String cursor() {
    return jdbc.queryForObject("select reactivation_erasure_after_account_id from scheduled_task_claim where task_name=?",
        String.class, ScopedAskerReactivation.SECRET_ERASURE_TASK);
  }

  private void removeHuman() {
    authenticated.setAccessToken(null);
    request.setUserPrincipal(null); request.removeHeader("Authorization");
    clearInvocations(broker);
  }

  @Test
  void erasedForbiddenRejectionReleasesOnlyItsClaimAndAllowsANewAuthorizedIntent() {
    historicalRejection(403);
  }

  @Test
  void erasedConflictRejectionReleasesOnlyItsClaimAndAllowsANewAuthorizedIntent() {
    historicalRejection(409);
  }

  private void historicalRejection(int status) {
    expectRead("a".repeat(64));
    var originalBody = new AtomicReference<String>();
    var refusal = new AtomicReference<String>();
    server.expect(requestTo(URL + "/reactivate")).andRespond(outbound -> {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      String body = ((MockClientHttpRequest) outbound).getBodyAsString();
      originalBody.set(body); refusal.set(signed(body, status));
      throw new ResourceAccessException("synthetic lost terminal response");
    });
    assertThrows(de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException.class,
        () -> facade.reactivateAsker(input(PASSWORD)));
    server.verify(); server.reset();
    var frozen = raw();
    clock.current = Instant.ofEpochSecond(frozen.path("metadata").path("authorityValidUntil").longValue() + 604800);
    var erased = (ObjectNode) frozen.deepCopy();
    erased.put("phase", "FROZEN_HISTORY");
    erased.put("envelope", codec.retainReactivationHistory(frozen.path("metadata"),
        frozen.path("envelope").textValue(), "FROZEN", null, actor));
    write(erased); renewHuman();
    Class<? extends RuntimeException> expected = switch (status) {
      case 400 -> de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException.class;
      case 403 -> de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class;
      case 409 -> de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class;
      default -> throw new AssertionError("Unknown synthetic status");
    };
    expectRefusal(originalBody.get(), refusal.get(), status);
    assertThrows(expected, () -> facade.reactivateAsker(input(PASSWORD)));
    server.verify(); server.reset();
    var declined = raw();
    assertEquals("NO_EFFECT_HISTORY", declined.path("phase").textValue());
    assertEquals(frozen.path("metadata"), declined.path("metadata"));
    assertNull(jdbc.queryForObject("select reactivation_operation_id from user where user_id=?", String.class, ACCOUNT));
    var verified = codec.verifyReactivationHistory(declined.path("metadata"),
        declined.path("envelope").textValue(), "NO_EFFECT_HISTORY", declined.path("receipt").textValue(), actor);
    assertEquals("NO_EFFECT", verified.phase());
    assertFalse(verified.template().path("change").has("password"));
    verify(lifecycle, never()).cancelUserDeletion(any(User.class));
    var invalidPost = (ObjectNode) declined.deepCopy();
    ((ObjectNode) invalidPost.path("postSql")).put("reactivation_operation_id", frozen.path("metadata").path("operationId").textValue());
    write(invalidPost);
    assertThrows(RuntimeException.class, () -> facade.reactivateAsker(input(PASSWORD)));
    write(declined);
    var afterRejection = jdbc.queryForMap("select * from user where user_id=?", ACCOUNT);
    expectRefusal(originalBody.get(), refusal.get(), status);
    assertThrows(expected, () -> facade.reactivateAsker(input(PASSWORD)));
    assertEquals(afterRejection, jdbc.queryForMap("select * from user where user_id=?", ACCOUNT));
    server.verify(); server.reset();
    clock.current = Instant.now(); renewHuman();
    expectRead("b".repeat(64));
    server.expect(requestTo(URL + "/reactivate")).andRespond(outbound -> {
      String body = ((MockClientHttpRequest) outbound).getBodyAsString();
      assertNotEquals(frozen.path("metadata").path("operationId"), codec.read(body, JsonNode.class).path("operationId"));
      assertTrue("synthetic-corrected-password".equals(codec.read(body, JsonNode.class).path("change").path("password").textValue()));
      return withSuccess(signed(body), MediaType.APPLICATION_JSON).createResponse(outbound);
    });
    assertDoesNotThrow(() -> facade.reactivateAsker(input("synthetic-corrected-password")));
    assertEquals("ACKNOWLEDGED", raw().path("phase").textValue());
    verify(lifecycle, times(1)).cancelUserDeletion(any(User.class));
    verifyNoInteractions(generic, repair); server.verify();
  }

  private void expectRefusal(String body, String reply, int status) {
    server.expect(requestTo(URL + "/reactivate")).andRespond(outbound -> {
      assertTrue(body.equals(((MockClientHttpRequest) outbound).getBodyAsString()));
      return org.springframework.test.web.client.response.MockRestResponseCreators
          .withStatus(org.springframework.http.HttpStatus.valueOf(status))
          .body(reply).contentType(MediaType.APPLICATION_JSON).createResponse(outbound);
    });
  }

  private void renewHuman() {
    String bearer = "fixture-human-" + ++token;
    authenticated.setAccessToken(bearer);
    var jwt =
        Jwt.withTokenValue(bearer)
            .header("alg", "RS256")
            .subject(HUMAN)
            .issuer(ISSUER)
            .claim("azp", "admin-ui")
            .claim("tenantId", "7")
            .issuedAt(clock.instant().minusSeconds(1))
            .expiresAt(clock.instant().plusSeconds(600))
            .build();
    request.setUserPrincipal(
        new JwtAuthenticationToken(
            jwt,
            List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    "ROLE_USER_ADMIN"))));
    request.removeHeader("Authorization");
    request.addHeader("Authorization", "Bearer " + bearer);
    when(machine.accessToken()).thenReturn("fixture-machine-" + token);
    when(machine.validatedJwtIssuedAt())
        .thenReturn(clock.instant().minusSeconds(1).getEpochSecond());
    when(machine.validatedJwtExpiresAt())
        .thenReturn(clock.instant().plusSeconds(600).getEpochSecond());
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
    return codec.read(
        jdbc.queryForObject(
            "select reactivation_scoped_json from user where user_id=?", String.class, ACCOUNT),
        JsonNode.class);
  }

  private void write(JsonNode raw) {
    jdbc.update(
        "update user set reactivation_scoped_json=? where user_id=?",
        codec.canonical(raw),
        ACCOUNT);
  }

  private void expectRead(String state) {
    var projection = new TreeMap<String, Object>();
    projection.put("id", ACCOUNT);
    projection.put("username", nativeUsername);
    projection.put("email", EMAIL);
    projection.put("firstName", null);
    projection.put("lastName", null);
    projection.put("enabled", false);
    projection.put("emailVerified", true);
    projection.put("tenantId", "7");
    projection.put("preferredLanguage", null);
    projection.put("effectiveRoles", List.of("user"));
    projection.put("passwordChangeRequired", false);
    projection.put("state", state);
    server
        .expect(requestTo(URL + "/read"))
        .andRespond(withSuccess(codec.canonical(projection), MediaType.APPLICATION_JSON));
  }

  private String signed(String raw) {
    return signed(raw, null);
  }

  private String signed(String raw, Integer status) {
    var body = codec.read(raw, JsonNode.class);
    var payload = new TreeMap<String, Object>();
    payload.put("realmId", REALM);
    payload.put("accountId", ACCOUNT);
    payload.put("operationId", body.path("operationId").textValue());
    payload.put("command", "reactivate");
    payload.put("ownerClient", actor.clientId());
    payload.put("ownerSubject", OWNER);
    payload.put("intentDigest", codec.digest("maintenance-intent", body));
    payload.put("previousState", body.path("expectedState").textValue());
    payload.put("tenantId", "7");
    payload.put(
        "originDigest", codec.digest("maintenance-origin", body.path("target").path("origin")));
    payload.put("result", status == null ? "UPDATED" : "NO_EFFECT");
    if (status != null) payload.put("status", status);
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(codec.canonical(payload).getBytes(StandardCharsets.UTF_8));
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
      var outer = new TreeMap<String, Object>();
      outer.put("operationId", body.path("operationId").textValue());
      outer.put("accountId", ACCOUNT);
      outer.put("result", status == null ? "UPDATED" : "NO_EFFECT");
      if (status != null) outer.put("status", status);
      outer.put("receipt", encoded + "." + HexFormat.of().formatHex(mac.doFinal(
          ((status == null ? "maintenance-operation-receipt" : "maintenance-no-effect-receipt/v1")
              + "\n" + encoded).getBytes(StandardCharsets.UTF_8))));
      return codec.canonical(outer);
    } catch (java.security.GeneralSecurityException impossible) {
      throw new IllegalStateException("Synthetic receipt unavailable");
    }
  }

  private static final class MutableClock extends Clock {
    private Instant current = Instant.now();

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return current;
    }
  }
}
