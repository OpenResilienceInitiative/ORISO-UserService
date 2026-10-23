package de.caritas.cob.userservice.api.adapters.keycloak.maintenance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

/** Actual private SQL owner + HTTP signed receiver double, not native grant acceptance. */
class SelfLocaleOwnedAccountsIT {
  private static final String ACCOUNT = "11111111-1111-4111-8111-111111111111";
  private static final String REALM = "22222222-2222-4222-8222-222222222222";
  private static final String SUBJECT = "33333333-3333-4333-8333-333333333333";
  private static final String ISSUER = "https://identity.test/realms/fixture";
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private static final LocalDateTime PRIMARY_BIRTH =
      LocalDateTime.parse("2026-10-20T10:11:12.123456");
  private static final LocalDateTime CONSULTANT_BIRTH =
      LocalDateTime.parse("2026-10-19T09:08:07.654321");

  @Test
  void tenantDualCapturesProtectedOwnerAndReplaysExactBodyAfterLostReply() throws Exception {
    try (var f = new Fixture("TENANT_ADMIN", true, 7)) {
      f.loseReply = true;
      assertThrows(RuntimeException.class, f::patch);
      assertEquals(1, f.effects.get());
      assertEquals(1, f.domainCalls());
      assertEquals(
          "PROTECTED_ACCOUNT_OPERATIONS_V1",
          f.target().path("origin").path("humanAuthority").textValue());
      String body = f.savedBody;
      String owner = f.owner();
      f.restart(f.transactions);
      assertDoesNotThrow(f::patch);
      assertEquals(body, f.savedBody);
      assertEquals(owner, f.owner());
      assertEquals(1, f.effects.get());
      assertEquals(1, f.reads.get());
      assertEquals(1, f.domainCalls());
      assertEquals("ACKNOWLEDGED", f.status());
    }
  }

  @Test
  void platformZeroTenantRequiresActualSuperAdminAndConsultantPair() throws Exception {
    try (var f = new Fixture("PLATFORM_ADMIN", true, 0)) {
      assertDoesNotThrow(f::patch);
      assertEquals("0", f.target().path("tenantId").textValue());
      assertEquals(
          "PROTECTED_ACCOUNT_OPERATIONS_V1",
          f.target().path("origin").path("humanAuthority").textValue());
      assertEquals(1, f.effects.get());
      assertEquals(1, f.domainCalls());
      assertEquals("ACKNOWLEDGED", f.status());
    }
  }

  @Test
  void ordinaryZeroTenantRowsCannotBorrowPlatformEligibility() throws Exception {
    for (String kind : List.of("USER", "CONSULTANT")) {
      try (var f = new Fixture(kind, false, 0)) {
        assertThrows(RuntimeException.class, f::patch);
        f.assertNoIntentOrRpc();
      }
    }
  }

  @Test
  void pureAdministratorsRemainIneligibleForTheExistingLanguageCallback() throws Exception {
    for (String kind : List.of("AGENCY_ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN")) {
      try (var f = new Fixture(kind, false, "PLATFORM_ADMIN".equals(kind) ? 0 : 7)) {
        assertThrows(RuntimeException.class, f::patch);
        f.assertNoIntentOrRpc();
      }
    }
  }

  @Test
  void foreignAdminMixedUserAndInvalidAdminKindRefuseBeforeIntentOrNativeRpc() throws Exception {
    for (String invalid : List.of("foreign", "mixed", "kind")) {
      try (var f = new Fixture("CONSULTANT", false, 7)) {
        f.addAdmin("TENANT", "foreign".equals(invalid) ? 8 : 7);
        if ("mixed".equals(invalid)) f.addUser();
        if ("kind".equals(invalid)) f.jdbc.update("update admin set type='UNKNOWN'");
        assertThrows(RuntimeException.class, f::patch);
        f.assertNoIntentOrRpc();
      }
    }
  }

  @Test
  void frozenOrdinaryIntentCannotAdoptAnAddedAgencyAdministratorPrimary() throws Exception {
    try (var f = new Fixture("CONSULTANT", false, 7)) {
      f.loseReply = true;
      assertThrows(RuntimeException.class, f::patch);
      String original = f.owner();
      f.addAdmin("AGENCY", 7);
      f.restart(f.transactions);
      assertThrows(RuntimeException.class, f::patch);
      assertEquals(original, f.owner());
      assertEquals(1, f.effects.get());
      assertEquals(1, f.reads.get());
      assertEquals(1, f.domainCalls());
      assertEquals("FROZEN", f.status());
    }
  }

  @Test
  void frozenIntentRefusesChangedPrimaryOrSecondarySqlBirth() throws Exception {
    for (String changed : List.of("admin", "consultant")) {
      try (var f = new Fixture("TENANT_ADMIN", true, 7)) {
        f.loseReply = true;
        assertThrows(RuntimeException.class, f::patch);
        String original = f.owner();
        f.jdbc.update(
            "update " + changed + " set create_date=?",
            Timestamp.valueOf(PRIMARY_BIRTH.plusDays(1)));
        assertThrows(RuntimeException.class, f::patch);
        assertEquals(original, f.owner());
        assertEquals(1, f.effects.get());
        assertEquals(1, f.reads.get());
        assertEquals(1, f.domainCalls());
        assertEquals("FROZEN", f.status());
      }
    }
  }

  @Test
  void nullOrMalformedOwnerCustodyCannotResumeFrozenOrAcknowledgedIntent() throws Exception {
    for (boolean frozen : List.of(true, false)) {
      for (String invalid : Arrays.asList(null, "{}", "{\"v\":1,\"kind\":\"CONSULTANT\"}")) {
        try (var f = new Fixture("CONSULTANT", false, 7)) {
          f.loseReply = frozen;
          if (frozen) assertThrows(RuntimeException.class, f::patch);
          else assertDoesNotThrow(f::patch);
          f.jdbc.update("update identity_account_operation_intent set source_json=?", invalid);
          assertThrows(RuntimeException.class, f::patch);
          assertEquals(1, f.effects.get());
          assertEquals(1, f.reads.get());
          assertEquals(1, f.domainCalls());
        }
      }
    }
  }

  @Test
  void exactSixOwnerFieldsPreserveSqlPrecisionAndIgnoreMutableUpdateDate() throws Exception {
    try (var f = new Fixture("AGENCY_ADMIN", true, 7)) {
      f.loseReply = true;
      assertThrows(RuntimeException.class, f::patch);
      assertNotNull(f.owner());
      var owner = f.codec.read(f.owner(), JsonNode.class);
      var fields = new HashSet<String>();
      owner.fieldNames().forEachRemaining(fields::add);
      assertEquals(
          Set.of(
              "v", "kind", "consultant", "tenantId", "primaryCreateDate", "consultantCreateDate"),
          fields);
      assertEquals(1, owner.path("v").intValue());
      assertEquals("AGENCY_ADMIN", owner.path("kind").textValue());
      assertTrue(owner.path("consultant").booleanValue());
      assertEquals(7, owner.path("tenantId").longValue());
      assertEquals(PRIMARY_BIRTH.toString(), owner.path("primaryCreateDate").textValue());
      assertEquals(CONSULTANT_BIRTH.toString(), owner.path("consultantCreateDate").textValue());
      String original = f.owner();
      f.jdbc.update("update admin set update_date=?", Timestamp.valueOf(PRIMARY_BIRTH.plusDays(2)));
      f.jdbc.update(
          "update consultant set update_date=?", Timestamp.valueOf(PRIMARY_BIRTH.plusDays(2)));
      assertDoesNotThrow(f::patch);
      assertEquals(original, f.owner());
      assertEquals(1, f.effects.get());
      assertEquals(1, f.domainCalls());
    }
  }

  @Test
  void anotherDataSourceTransactionCannotOwnTheActualLocaleRows() throws Exception {
    try (var f = new Fixture("CONSULTANT", false, 7)) {
      var other =
          new DriverManagerDataSource("jdbc:h2:mem:locale-foreign-" + UUID.randomUUID(), "sa", "");
      f.restart(new DataSourceTransactionManager(other));
      assertThrows(RuntimeException.class, f::patch);
      f.assertNoIntentOrRpc();
    }
  }

  private static final class Fixture implements AutoCloseable {
    final DriverManagerDataSource source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:locale-owner-"
                + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER",
            "sa",
            "");
    final JdbcTemplate jdbc = new JdbcTemplate(source);
    final PlatformTransactionManager transactions = new DataSourceTransactionManager(source);
    final IdentityClientConfig mode = mock(IdentityClientConfig.class);
    final TaskActorConfiguration actors = mock(TaskActorConfiguration.class);
    final TaskActorTokenBroker broker = mock(TaskActorTokenBroker.class);
    final AccountLifecycleCodec codec = new AccountLifecycleCodec(KEY);
    final TaskActorCredentials actor =
        new TaskActorCredentials(
            TaskActor.ACCOUNT_ADMIN, "backend-account-admin", "fixture-secret", SUBJECT);
    final MockHttpServletRequest request =
        new MockHttpServletRequest("PATCH", "/service/users/data");
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    final AtomicInteger effects = new AtomicInteger(), reads = new AtomicInteger();
    final String operation = UUID.randomUUID().toString();
    final long tenant;
    final AuthenticatedUser human;
    SelfLocaleMaintenance maintenance;
    volatile String savedBody, savedReply;
    volatile boolean loseReply;
    volatile Throwable handlerFailure;

    Fixture(String kind, boolean dual, long tenant) throws Exception {
      this.tenant = tenant;
      jdbc.execute(
          "create table user(user_id varchar(36) primary key,tenant_id bigint,delete_date timestamp,email varchar(320),create_date timestamp(6),update_date timestamp(6),language_code varchar(8),patch_count int)");
      jdbc.execute(
          "create table consultant(consultant_id varchar(36) primary key,tenant_id bigint,delete_date timestamp,email varchar(320),create_date timestamp(6),update_date timestamp(6),language_code varchar(8),patch_count int)");
      jdbc.execute(
          "create table admin(admin_id varchar(36) primary key,tenant_id bigint,type varchar(32),email varchar(320),create_date timestamp(6),update_date timestamp(6))");
      if ("USER".equals(kind)) addUser();
      if ("CONSULTANT".equals(kind) || dual)
        jdbc.update(
            "insert into consultant values(?,?,null,'same@fixture.invalid',?,?,'en',0)",
            ACCOUNT,
            tenant,
            Timestamp.valueOf(CONSULTANT_BIRTH),
            Timestamp.valueOf(CONSULTANT_BIRTH));
      if (kind.endsWith("ADMIN"))
        addAdmin(
            switch (kind) {
              case "AGENCY_ADMIN" -> "AGENCY";
              case "TENANT_ADMIN" -> "TENANT";
              case "PLATFORM_ADMIN" -> "SUPER";
              default -> throw new IllegalArgumentException();
            },
            tenant);
      new ResourceDatabasePopulator(
              new ClassPathResource(
                  "db/changelog/changeset/20261021_identity_self_locale_367/migrate.sql"),
              new ClassPathResource(
                  "db/changelog/changeset/20261023_existing_import_intent_367/migrate.sql"))
          .execute(source);
      // Verify complete fixture input independently before any production assertion.
      for (String table : List.of("user", "consultant", "admin"))
        jdbc.queryForList("select tenant_id,email,create_date from " + table);
      human =
          new AuthenticatedUser(
              ACCOUNT, "same", Set.of("consultant"), "fixture-human", Set.of("ROLE_CONSULTANT"));
      human.setTenantId(tenant);
      var jwt =
          Jwt.withTokenValue("fixture-human")
              .header("alg", "RS256")
              .subject(ACCOUNT)
              .issuer(ISSUER)
              .claim("azp", "app")
              .claim("tenantId", Long.toString(tenant))
              .issuedAt(Instant.now().minusSeconds(10))
              .expiresAt(Instant.now().plusSeconds(600))
              .build();
      request.setUserPrincipal(new JwtAuthenticationToken(jwt, List.of()));
      request.addHeader("Authorization", "Bearer fixture-human");
      request.addHeader("Idempotency-Key", operation);
      when(mode.isFiveActorMode()).thenReturn(true);
      when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(actor);
      var checked = mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
      when(checked.accessToken()).thenReturn("fixture-admin");
      when(checked.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);
      when(checked.purpose()).thenReturn(TaskPurpose.ACCOUNT_MAINTENANCE);
      when(checked.clientId()).thenReturn(actor.clientId());
      when(checked.subject()).thenReturn(SUBJECT);
      when(checked.issuer()).thenReturn(ISSUER);
      when(checked.validatedJwtIssuedAt())
          .thenReturn(Instant.now().minusSeconds(1).getEpochSecond());
      when(checked.validatedJwtExpiresAt())
          .thenReturn(Instant.now().plusSeconds(600).getEpochSecond());
      when(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(checked);
      server.createContext(
          "/",
          exchange -> {
            try {
              assertEquals("POST", exchange.getRequestMethod());
              assertEquals(
                  "Bearer fixture-admin", exchange.getRequestHeaders().getFirst("Authorization"));
              assertEquals(
                  "Bearer fixture-human",
                  exchange.getRequestHeaders().getFirst("X-ORISO-Human-Authorization"));
              assertNotNull(exchange.getRequestHeaders().getFirst("X-ORISO-Task-Context"));
              String wire =
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
              String response;
              if (exchange.getRequestURI().getPath().endsWith("/read")) {
                reads.incrementAndGet();
                var projection = codec.read("{}", ObjectNode.class);
                projection
                    .put("id", ACCOUNT)
                    .put("username", "same")
                    .put("email", "same@fixture.invalid")
                    .putNull("firstName")
                    .putNull("lastName");
                projection
                    .put("enabled", true)
                    .put("emailVerified", true)
                    .put("tenantId", Long.toString(tenant))
                    .put("preferredLanguage", "en")
                    .put("passwordChangeRequired", false)
                    .put("state", "a".repeat(64));
                projection.putArray("effectiveRoles").add("consultant");
                response = codec.canonical(projection);
              } else {
                assertEquals("FROZEN", status());
                assertEquals(1, domainCalls());
                if (savedBody == null) {
                  savedBody = wire;
                  savedReply = signedReply(codec.read(wire, JsonNode.class));
                  effects.incrementAndGet();
                  if (loseReply) {
                    loseReply = false;
                    exchange.sendResponseHeaders(503, -1);
                    return;
                  }
                } else assertEquals(savedBody, wire);
                response = savedReply;
              }
              byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().add("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
            } catch (Throwable failure) {
              handlerFailure = failure;
              exchange.sendResponseHeaders(500, -1);
            } finally {
              exchange.close();
            }
          });
      server.start();
      restart(transactions);
    }

    void addUser() {
      jdbc.update(
          "insert into user values(?,?,null,'same@fixture.invalid',?,?,'en',0)",
          ACCOUNT,
          tenant,
          Timestamp.valueOf(PRIMARY_BIRTH),
          Timestamp.valueOf(PRIMARY_BIRTH));
    }

    void addAdmin(String type, long ownerTenant) {
      jdbc.update(
          "insert into admin values(?,?,?,'same@fixture.invalid',?,?)",
          ACCOUNT,
          ownerTenant,
          type,
          Timestamp.valueOf(PRIMARY_BIRTH),
          Timestamp.valueOf(PRIMARY_BIRTH));
    }

    void restart(PlatformTransactionManager owner) {
      maintenance =
          new SelfLocaleMaintenance(
              mode,
              human,
              request,
              jdbc,
              owner,
              actors,
              broker,
              codec,
              new RestTemplate(),
              Clock.systemUTC(),
              "http://127.0.0.1:" + server.getAddress().getPort(),
              ISSUER,
              "fixture",
              REALM);
    }

    Optional<Map<String, Object>> patch() {
      var patch = Map.<String, Object>of("id", ACCOUNT, "preferredLanguage", "de");
      return maintenance.patch(
          "de",
          patch,
          () -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            String table =
                jdbc.queryForObject("select count(*) from user", Integer.class) == 1
                    ? "user"
                    : "consultant";
            jdbc.update("update " + table + " set language_code='de',patch_count=patch_count+1");
            return Optional.of(Map.of("id", ACCOUNT));
          });
    }

    int domainCalls() {
      return jdbc.queryForObject(
          "select coalesce((select sum(patch_count) from user),0)+coalesce((select sum(patch_count) from consultant),0)",
          Integer.class);
    }

    String status() {
      return jdbc.queryForObject(
          "select status from identity_account_operation_intent where operation_id=?",
          String.class,
          operation);
    }

    String owner() {
      return jdbc.queryForObject(
          "select source_json from identity_account_operation_intent where operation_id=?",
          String.class,
          operation);
    }

    JsonNode target() {
      return codec.read(
          jdbc.queryForObject(
              "select target_json from identity_account_operation_intent where operation_id=?",
              String.class,
              operation),
          JsonNode.class);
    }

    void assertNoIntentOrRpc() {
      assertEquals(
          0,
          jdbc.queryForObject(
              "select count(*) from identity_account_operation_intent", Integer.class));
      assertEquals(0, domainCalls());
      assertEquals(0, reads.get());
      assertEquals(0, effects.get());
    }

    String signedReply(JsonNode body) throws Exception {
      var facts = codec.read("{}", ObjectNode.class);
      facts
          .put("realmId", REALM)
          .put("accountId", ACCOUNT)
          .put("operationId", operation)
          .put("command", "locale")
          .put("ownerClient", actor.clientId())
          .put("ownerSubject", SUBJECT)
          .put("intentDigest", codec.digest("maintenance-intent", body))
          .put("previousState", body.path("expectedState").textValue())
          .put("tenantId", Long.toString(tenant))
          .put(
              "originDigest",
              codec.digest("maintenance-origin", body.path("target").path("origin")))
          .put("result", "UPDATED");
      String encoded =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(codec.canonical(facts).getBytes(StandardCharsets.UTF_8));
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
      String receipt =
          encoded
              + "."
              + HexFormat.of()
                  .formatHex(
                      mac.doFinal(
                          ("maintenance-operation-receipt\n" + encoded)
                              .getBytes(StandardCharsets.UTF_8)));
      return codec.canonical(
          Map.of(
              "operationId",
              operation,
              "accountId",
              ACCOUNT,
              "result",
              "UPDATED",
              "receipt",
              receipt));
    }

    public void close() {
      server.stop(0);
      assertNull(handlerFailure, "Fixture HTTP input/oracle failed; cannot count as product RED");
    }
  }
}
