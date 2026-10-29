package de.caritas.cob.userservice.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.ClassUtils;

/** Additional public native capture proof; never claims successful Matrix/onboarding delivery. */
class TrackedConsultantCreationNativePairProof {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String KEY = "X-ORISO-Creation-Operation-Key";
  private static final String RECEIPT = "X-ORISO-Creation-Operation";
  private static final String ROUTE = "/useradmin/consultant-creation-operations/";
  private static final Set<String> RECEIPT_FIELDS =
      Set.of(
          "operationId",
          "nativeId",
          "version",
          "scope",
          "dispatch",
          "workflow",
          "row",
          "onboarding",
          "compensation",
          "reason",
          "createdAt",
          "updatedAt",
          "dispatchedAt",
          "capturedAt");
  private static final Set<String> FLAGS =
      Set.of(
          "productionClasspath",
          "fiveActors",
          "actualServletSecurity",
          "actualJpa",
          "scopedOwners",
          "creationSchema",
          "bootNoEffect",
          "matrixNonceReached",
          "durableNativeCapture",
          "receiptById",
          "receiptByKey",
          "matchingReplay",
          "changedBodyConflict",
          "unauthorizedConcealed",
          "unknownConcealed",
          "nativeCheckpoint",
          "failureReceipt",
          "rolledBackWithoutConsultant",
          "noMatrixRegistration",
          "tenantPolicyReached",
          "contextClosed");

  @Test
  void trackedPublicCreationCapturesNativeIdentityBeforeFailureAndNeverRedispatches()
      throws Exception {
    var witness = new TreeMap<String, Boolean>();
    FLAGS.forEach(flag -> witness.put(flag, false));
    var nonceReached = new CountDownLatch(1);
    var releaseNonce = new CountDownLatch(1);
    var nonceReads = new AtomicInteger();
    var unexpectedMatrix = new AtomicBoolean();
    var expiredNonce = new AtomicBoolean();
    var singleTenantReads = new AtomicInteger();
    var policyReads = new AtomicInteger();
    var unexpectedTenant = new AtomicBoolean();
    var matrix = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var tenant = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ConfigurableApplicationContext context = null;
    var exchange = Path.of(required("TRACKED_CREATION_PRIVATE_EXCHANGE"));
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      matrix.createContext(
          "/",
          request -> {
            try {
              if (!"GET".equals(request.getRequestMethod())
                  || !"/_synapse/admin/v1/register".equals(request.getRequestURI().toString())
                  || request.getRequestBody().readNBytes(1).length != 0) {
                unexpectedMatrix.set(true);
                request.sendResponseHeaders(403, -1);
                return;
              }
              nonceReads.incrementAndGet();
              nonceReached.countDown();
              try {
                if (!releaseNonce.await(8, TimeUnit.SECONDS)) expiredNonce.set(true);
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                expiredNonce.set(true);
              }
              request.sendResponseHeaders(503, -1);
            } finally {
              request.close();
            }
          });
      matrix.start();
      tenant.createContext(
          "/",
          request -> {
            try {
              String path = request.getRequestURI().toString();
              if (!"GET".equals(request.getRequestMethod())
                  || !Set.of("/tenant/public/", "/tenant/public/id/11").contains(path)
                  || request.getRequestBody().readNBytes(1).length != 0) {
                unexpectedTenant.set(true);
                request.sendResponseHeaders(403, -1);
                return;
              }
              if (path.equals("/tenant/public/")) {
                if (!runtimeBearer(request.getRequestHeaders().getFirst("Authorization"))) {
                  unexpectedTenant.set(true);
                  request.sendResponseHeaders(403, -1);
                  return;
                }
                singleTenantReads.incrementAndGet();
              } else policyReads.incrementAndGet();
              byte[] body =
                  ("{\"id\":11,\"subdomain\":\"fixture-11\",\"settings\":{\"tenantAdminControls\":{"
                          + "\"chatRecoverySettings\":{\"consultant\":\"RECOVERY_KEY\",\"asker\":\"RECOVERY_KEY\",\"revision\":1},"
                          + "\"accountInactivitySettings\":{\"askerMonths\":18,\"consultantMonths\":18,\"otherMonths\":18,\"revision\":1}}}}")
                      .getBytes(StandardCharsets.UTF_8);
              request.getResponseHeaders().set("Content-Type", "application/json");
              request.getResponseHeaders().set("Cache-Control", "no-store");
              request.sendResponseHeaders(200, body.length);
              request.getResponseBody().write(body);
            } finally {
              request.close();
            }
          });
      tenant.start();
      verifyClasspath();
      witness.put("productionClasspath", true);
      var application = new SpringApplication(UserServiceApplication.class);
      application.setWebApplicationType(WebApplicationType.SERVLET);
      application.setAdditionalProfiles("testing");
      application.setRegisterShutdownHook(false);
      application.setDefaultProperties(
          Map.of(
              "server.port", "0", "spring.main.banner-mode", "off", "logging.level.root", "OFF"));
      context =
          application.run(
              "--server.port=0",
              "--spring.profiles.active=testing",
              "--logging.level.root=OFF",
              "--logging.level.org.springframework.web=OFF",
              "--tenant.service.api.url=http://127.0.0.1:" + tenant.getAddress().getPort(),
              "--matrix.apiUrl=http://127.0.0.1:" + matrix.getAddress().getPort(),
              "--spring.sql.init.data-locations=classpath:database/UserServiceDatabase.sql,classpath:database/AccountInactivityDatabase.sql,file:"
                  + required("STARTUP_JOURNAL_FIXTURE"));
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();
      assertTrue(
          port > 0 && !context.getBeansOfType(SecurityFilterChain.class).isEmpty(),
          "Actual servlet security required");
      verifyOwners(context);
      assertEquals(
          Boolean.TRUE,
          context
              .getBean(
                  de.caritas.cob.userservice.api.config.auth.ServiceIdentitySeparationCheck.class)
              .scopedAccountCommands());
      witness.put("fiveActors", true);
      witness.put("scopedOwners", true);
      var jpa = context.getBean(org.springframework.transaction.PlatformTransactionManager.class);
      assertInstanceOf(JpaTransactionManager.class, jpa);
      var source = context.getBean(DataSource.class);
      assertTrue(
          ((JpaTransactionManager) jpa).getDataSource() == source
              && ((JpaTransactionManager) jpa).getEntityManagerFactory().isOpen(),
          "Same actual JPA DataSource required");
      witness.put("actualJpa", true);
      applyCreationMigrations(
          source, context.getEnvironment().getRequiredProperty("spring.datasource.url"));
      var jdbc = new JdbcTemplate(source);
      assertEquals(
          0L,
          jdbc.queryForObject(
              "select count(*) from account_creation_attempt where receipt_id is not null",
              Long.class));
      assertEquals(
          0L,
          jdbc.queryForObject(
              "select count(*) from account_creation_attempt where request_fingerprint is not null",
              Long.class));
      witness.put("creationSchema", true);
      assertEquals(401, send(client, port, null, "GET", "/users/data", null, null).statusCode());
      witness.put("actualServletSecurity", true);
      Files.writeString(exchange.resolve("boot-ready"), "BOOT_READY");
      JsonNode fixture = awaitJson(exchange.resolve("boot-verified.json"), Duration.ofSeconds(25));
      assertTrue(fixture.path("bootNoEffect").asBoolean(false));
      witness.put("bootNoEffect", true);
      seedPrimary(context, fixture.path("primary"));
      seedOther(context, fixture.path("other"));
      String bearer = fixture.path("primaryBearer").asText();
      String operationKey = UUID.randomUUID().toString();
      String username = "tracked-native-" + UUID.randomUUID();
      String email = username + "@fixture.invalid";
      var body =
          JSON.createObjectNode()
              .put("username", username)
              .put("email", email)
              .put("firstname", "Tracked")
              .put("lastname", "Fixture")
              .put("formalLanguage", true)
              .put("absent", false)
              .put("tenantId", 11)
              .put("isGroupchatConsultant", false);
      body.putArray("agencyIds");
      body.putArray("topicIds");
      String originalBody = JSON.writeValueAsString(body);
      var first =
          client.sendAsync(
              request(port, bearer, "POST", "/useradmin/consultants", operationKey, originalBody),
              HttpResponse.BodyHandlers.ofString());
      assertTrue(
          nonceReached.await(20, TimeUnit.SECONDS),
          "Actual Matrix nonce must be reached after native capture");
      witness.put("matrixNonceReached", true);
      assertFalse(
          first.isDone(), "Original request must remain inside actual Matrix nonce boundary");
      var rows =
          jdbc.queryForList(
              "select receipt_id,native_identity_id,dispatch from account_creation_attempt where"
                  + " operation_key=?",
              operationKey);
      assertTrue(rows.size() == 1, "One outer owner required");
      var row = rows.getFirst();
      String operation = Objects.toString(row.get("receipt_id"), "");
      String nativeId = Objects.toString(row.get("native_identity_id"), "");
      assertTrue(
          validUuid(operation) && validUuid(nativeId) && !operation.equals(operationKey),
          "Server receipt UUID and captured native UUID required");
      assertTrue(
          "NATIVE_ID_CAPTURED".equals(row.get("dispatch")), "Durable capture must precede Matrix");
      var inner =
          jdbc.queryForList(
              "select account_id,creation_proof,status from identity_lifecycle_attempt where id=?",
              operation);
      assertTrue(
          inner.size() == 1
              && nativeId.equals(inner.getFirst().get("account_id"))
              && "OPEN".equals(inner.getFirst().get("status"))
              && inner.getFirst().get("creation_proof") instanceof String proof
              && !proof.isBlank(),
          "Actual native receipt must be durably linked before local saga; no fabricated capture");
      assertEquals(
          0L,
          jdbc.queryForObject("select count(*) from consultant where id=?", Long.class, nativeId));
      witness.put("durableNativeCapture", true);
      privateJson(
          exchange.resolve("captured.json"),
          Map.of(
              "account", nativeId, "operation", operation, "username", username, "email", email));
      String receiptPath = ROUTE + operation;
      var byId = send(client, port, bearer, "GET", receiptPath, null, null);
      verifyReceipt(byId, operation, nativeId, receiptPath, false);
      witness.put("receiptById", true);
      var byKey = send(client, port, bearer, "GET", ROUTE + "by-key/" + operationKey, null, null);
      verifyReceipt(byKey, operation, nativeId, receiptPath, false);
      witness.put("receiptByKey", true);
      var replay =
          send(client, port, bearer, "POST", "/useradmin/consultants", operationKey, originalBody);
      verifyReplay(replay, receiptPath, "CREATION_OPERATION_REPLAY");
      witness.put("matchingReplay", true);
      var changed = body.deepCopy().put("firstname", "Different");
      verifyReplay(
          send(
              client,
              port,
              bearer,
              "POST",
              "/useradmin/consultants",
              operationKey,
              JSON.writeValueAsString(changed)),
          receiptPath,
          "CREATION_OPERATION_CONFLICT");
      witness.put("changedBodyConflict", true);
      verifyConcealed(
          send(client, port, fixture.path("otherBearer").asText(), "GET", receiptPath, null, null));
      witness.put("unauthorizedConcealed", true);
      verifyConcealed(send(client, port, bearer, "GET", ROUTE + UUID.randomUUID(), null, null));
      witness.put("unknownConcealed", true);
      assertTrue(
          awaitJson(exchange.resolve("capture-verified.json"), Duration.ofSeconds(6))
              .path("nativeCheckpoint")
              .asBoolean(false));
      assertFalse(
          first.isDone(), "Replay and native checkpoint must finish before original Matrix reply");
      assertFalse(expiredNonce.get(), "Never extend production ten-second Matrix timeout");
      witness.put("nativeCheckpoint", true);
      releaseNonce.countDown();
      var failed = first.get(10, TimeUnit.SECONDS);
      assertEquals(
          503, failed.statusCode(), "Bound unconfirmed Matrix failure must be safe unavailable");
      verifyHeaders(failed, receiptPath);
      assertTrue(
          "CREATION_OPERATION_UNAVAILABLE"
              .equals(JSON.readTree(failed.body()).path("code").asText()),
          "Fixed private failure response required");
      var retained = send(client, port, bearer, "GET", receiptPath, null, null);
      verifyReceipt(retained, operation, nativeId, receiptPath, true);
      witness.put("failureReceipt", true);
      assertEquals(
          0L,
          jdbc.queryForObject("select count(*) from consultant where id=?", Long.class, nativeId));
      assertEquals(
          1L,
          jdbc.queryForObject(
              "select count(*) from account_creation_attempt where operation_key=?",
              Long.class,
              operationKey));
      witness.put("rolledBackWithoutConsultant", true);
      verifyReplay(
          send(client, port, bearer, "POST", "/useradmin/consultants", operationKey, originalBody),
          receiptPath,
          "CREATION_OPERATION_REPLAY");
      assertEquals(1, nonceReads.get());
      assertFalse(unexpectedMatrix.get());
      witness.put("noMatrixRegistration", true);
      assertTrue(
          singleTenantReads.get() > 0 && policyReads.get() >= 2 && !unexpectedTenant.get(),
          "Actual finite tenant resolution and policy routes required");
      witness.put("tenantPolicyReached", true);
    } finally {
      releaseNonce.countDown();
      if (context != null) {
        context.close();
        witness.put("contextClosed", !context.isActive());
      }
      matrix.stop(0);
      tenant.stop(0);
      Files.writeString(
          Path.of(required("TRACKED_CREATION_WITNESS")), JSON.writeValueAsString(witness));
    }
    for (String flag : FLAGS) assertTrue(witness.get(flag), flag);
  }

  private static void applyCreationMigrations(DataSource source, String configuredUrl)
      throws Exception {
    // Hibernate creates this entity in testing. Replace only its empty disposable table so the
    // two shipped XML migrations, not copied DDL, define this proof's actual journal schema.
    String expected = configuredUrl.split(";", 2)[0];
    assertTrue(
        expected.matches(
            "jdbc:h2:mem:tracked-native-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
        "Only the fixed fresh in-memory fixture is eligible");
    try (var connection = source.getConnection()) {
      assertTrue(
          "H2".equals(connection.getMetaData().getDatabaseProductName())
              && expected.equals(connection.getMetaData().getURL()),
          "Never replace a remote or external schema");
      try (var statement = connection.createStatement()) {
        try (var rows = statement.executeQuery("select count(*) from account_creation_attempt")) {
          assertTrue(
              rows.next() && rows.getLong(1) == 0 && !rows.next(),
              "Only the empty fresh fixture table may be replaced");
        }
        statement.execute("drop table account_creation_attempt");
      }
    }
    for (String path :
        List.of("20261023_account_creation_attempt", "20261029_account_creation_receipt")) {
      var migration = new SpringLiquibase();
      migration.setDataSource(source);
      migration.setChangeLog("classpath:db/changelog/changeset/" + path + "/changeSet.xml");
      migration.setShouldRun(true);
      migration.afterPropertiesSet();
    }
    var jdbc = new JdbcTemplate(source);
    assertTrue(
        jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class)
            .equals(
                List.of("20261023-account-creation-attempt", "20261029-account-creation-receipt")),
        "Both actual changesets must execute in master order");
    Set<String> columns =
        new HashSet<>(
            jdbc.queryForList(
                "select column_name from information_schema.columns where table_schema='public' and"
                    + " table_name='account_creation_attempt'",
                String.class));
    assertTrue(
        columns.equals(
            Set.of(
                "id",
                "operation_key",
                "owner_id",
                "tenant_id",
                "native_identity_id",
                "status",
                "created_at",
                "updated_at",
                "receipt_id",
                "agency_ids",
                "creation_role",
                "fingerprint_version",
                "request_fingerprint",
                "receipt_version",
                "dispatched_at",
                "captured_at",
                "dispatch",
                "workflow",
                "consultant_row",
                "onboarding",
                "compensation",
                "reason")),
        "Exact shipped receipt columns required");
  }

  private static void verifyOwners(ConfigurableApplicationContext context) throws Exception {
    assertEquals(
        "FIVE_ACTORS", context.getEnvironment().getRequiredProperty("TASK_IDENTITY_MODEL"));
    assertEquals("false", context.getEnvironment().getRequiredProperty("multitenancy.enabled"));
    for (String suffix : List.of("_CLIENT_ID", "_CLIENT_SECRET", "_SERVICE_SUBJECT")) {
      var values = new HashSet<String>();
      for (String actor : List.of("SETUP", "ACCOUNT_CREATE", "ACCOUNT_ADMIN", "MAIL", "RUNTIME"))
        values.add(context.getEnvironment().getRequiredProperty("IDENTITY_" + actor + suffix));
      assertEquals(5, values.size(), "Five distinct configured identities required");
    }
    Map<String, String> catalog =
        JSON.readValue(required("STARTUP_BEAN_CATALOG"), new TypeReference<>() {});
    for (var entry : catalog.entrySet()) {
      if (Set.of("serviceIdentitySeparationCheck", "entityManagerFactory").contains(entry.getKey()))
        continue;
      String expected =
          entry.getValue().startsWith("org.") || entry.getValue().startsWith("com.")
              ? entry.getValue()
              : "de.caritas.cob.userservice.api." + entry.getValue();
      assertTrue(
          expected.equals(
              ClassUtils.getUserClass(AopUtils.getTargetClass(context.getBean(entry.getKey())))
                  .getName()),
          "Exact production owner types required");
    }
  }

  private static void verifyClasspath() throws Exception {
    Path classes = Path.of(required("STARTUP_TEST_CLASSES"));
    String binary = TrackedConsultantCreationNativePairProof.class.getName().replace('.', '/');
    try (var paths = Files.walk(classes)) {
      var files = paths.filter(p -> p.toString().endsWith(".class")).toList();
      assertTrue(
          !files.isEmpty()
              && files.stream()
                  .allMatch(
                      p -> {
                        String name = classes.relativize(p).toString().replace('\\', '/');
                        return name.equals(binary + ".class") || name.startsWith(binary + "$");
                      }),
          "Only declared proof bytecode permitted");
    }
    assertTrue(
        TrackedConsultantCreationNativePairProof.class
                .getClassLoader()
                .getResource("de/caritas/cob/userservice/api/testConfig/KeycloakTestConfig.class")
            == null,
        "Foreign test configuration absent");
    assertTrue(
        Path.of(
                UserServiceApplication.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI())
            .toRealPath()
            .equals(Path.of(required("STARTUP_PRODUCTION_CLASSES")).toRealPath()),
        "Actual production classpath required");
  }

  private static void seedPrimary(ConfigurableApplicationContext context, JsonNode nativeUser) {
    var factory = context.getBean(jakarta.persistence.EntityManagerFactory.class);
    var entities = factory.createEntityManager();
    String id = nativeUser.path("id").asText();
    assertTrue(
        validUuid(id)
            && nativeUser.path("attributes").path("tenantId").path(0).asText().equals("0"));
    try {
      entities.getTransaction().begin();
      var now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
      var admin =
          de.caritas.cob.userservice.api.model.Admin.builder()
              .id(id)
              .type(de.caritas.cob.userservice.api.model.Admin.AdminType.SUPER)
              .tenantId(0L)
              .username(nativeUser.path("username").asText())
              .firstName(nativeUser.path("firstName").asText())
              .lastName(nativeUser.path("lastName").asText())
              .email(nativeUser.path("email").asText())
              .createDate(now)
              .updateDate(now)
              .build();
      entities.persist(admin);
      entities.getTransaction().commit();
    } finally {
      entities.close();
    }
    long birth = nativeUser.path("createdTimestamp").asLong();
    assertTrue(birth > 0);
    context
        .getBean(
            de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService
                .class)
        .assignAtCreation(id, 0L, 18, 1, java.time.Instant.ofEpochMilli(birth));
  }

  private static void seedOther(ConfigurableApplicationContext context, JsonNode nativeUser) {
    String id = nativeUser.path("id").asText();
    assertTrue(
        validUuid(id)
            && nativeUser.path("attributes").path("tenantId").path(0).asText().equals("11"));
    var user =
        new de.caritas.cob.userservice.api.model.User(
            id,
            null,
            nativeUser.path("username").asText(),
            nativeUser.path("email").asText(),
            false);
    user.setTenantId(11L);
    user.setLanguageCode(com.neovisionaries.i18n.LanguageCode.en);
    user.setEncourage2fa(false);
    user.setDeletionLifecycleState(
        de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState.ACTIVE);
    context.getBean(de.caritas.cob.userservice.api.port.out.UserRepository.class).save(user);
    long birth = nativeUser.path("createdTimestamp").asLong();
    assertTrue(birth > 0);
    context
        .getBean(
            de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService
                .class)
        .assignAtCreation(id, 11L, 18, 1, java.time.Instant.ofEpochMilli(birth));
  }

  private static void verifyReceipt(
      HttpResponse<String> response, String operation, String nativeId, String path, boolean failed)
      throws Exception {
    assertEquals(200, response.statusCode());
    verifyHeaders(response, path);
    JsonNode value = JSON.readTree(response.body());
    var fields = new HashSet<String>();
    value.fieldNames().forEachRemaining(fields::add);
    assertTrue(RECEIPT_FIELDS.equals(fields), "Receipt contains only the exact public projection");
    var scopeFields = new HashSet<String>();
    value.path("scope").fieldNames().forEachRemaining(scopeFields::add);
    assertTrue(
        Set.of("tenantId", "agencyIds", "role").equals(scopeFields), "Exact public scope only");
    assertTrue(
        operation.equals(value.path("operationId").asText())
            && nativeId.equals(value.path("nativeId").asText())
            && value.path("version").isIntegralNumber()
            && value.path("version").asLong() >= 1
            && value.path("scope").path("tenantId").asLong() == 11
            && value.path("scope").path("agencyIds").isArray()
            && value.path("scope").path("agencyIds").isEmpty()
            && "COUNSELLOR".equals(value.path("scope").path("role").asText())
            && "NATIVE_ID_CAPTURED".equals(value.path("dispatch").asText()),
        "Original authorized captured receipt required");
    assertTrue(
        "NOT_ATTEMPTED".equals(value.path("onboarding").asText()),
        "No setup issuer credit before local return");
    assertTrue(
        "NOT_STARTED".equals(value.path("compensation").asText()),
        "No invented compensation observation");
    if (failed)
      assertTrue(
          "FAILED".equals(value.path("workflow").asText())
              && "ROLLED_BACK".equals(value.path("row").asText())
              && "WORKFLOW_FAILED".equals(value.path("reason").asText()),
          "Actual rollback and fixed failure dimensions required");
  }

  private static void verifyReplay(HttpResponse<String> response, String path, String code)
      throws Exception {
    assertEquals(409, response.statusCode());
    verifyHeaders(response, path);
    JsonNode value = JSON.readTree(response.body());
    assertTrue(
        value.size() == 1 && code.equals(value.path("code").asText()),
        "Fixed replay/conflict response required");
  }

  private static void verifyConcealed(HttpResponse<String> response) {
    assertEquals(404, response.statusCode());
    assertTrue(
        response.headers().firstValue(RECEIPT).isEmpty(), "Unauthorized existence concealed");
  }

  private static void verifyHeaders(HttpResponse<String> response, String path) {
    assertTrue(
        path.equals(response.headers().firstValue(RECEIPT).orElse(null)),
        "Original receipt URI retained");
    assertTrue(
        "no-store".equals(response.headers().firstValue("Cache-Control").orElse(null)),
        "Receipts never cached");
  }

  private static HttpRequest request(
      int port, String bearer, String method, String path, String key, String body) {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(25));
    if (bearer != null) request.header("Authorization", "Bearer " + bearer);
    if (key != null) request.header(KEY, key);
    if (body != null) request.header("Content-Type", "application/json");
    return request
        .method(
            method,
            body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body))
        .build();
  }

  private static HttpResponse<String> send(
      HttpClient client,
      int port,
      String bearer,
      String method,
      String path,
      String key,
      String body)
      throws Exception {
    return client.send(
        request(port, bearer, method, path, key, body), HttpResponse.BodyHandlers.ofString());
  }

  private static boolean runtimeBearer(String authorization) {
    try {
      if (authorization == null || !authorization.startsWith("Bearer ")) return false;
      String[] segments = authorization.substring(7).split("\\.");
      if (segments.length != 3) return false;
      JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(segments[1]));
      return required("IDENTITY_RUNTIME_SERVICE_SUBJECT").equals(claims.path("sub").asText())
          && required("IDENTITY_RUNTIME_CLIENT_ID").equals(claims.path("azp").asText());
    } catch (Exception failure) {
      return false;
    }
  }

  private static JsonNode awaitJson(Path file, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!Files.exists(file)) {
      assertTrue(System.nanoTime() < deadline, "Bounded private native checkpoint required");
      Thread.sleep(10);
    }
    return JSON.readTree(Files.readString(file));
  }

  private static void privateJson(Path file, Object value) throws Exception {
    Path pending = file.resolveSibling(file.getFileName() + ".pending");
    Files.createFile(
        pending,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(pending, JSON.writeValueAsString(value));
    Files.move(pending, file);
  }

  private static boolean validUuid(String value) {
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException failure) {
      return false;
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Required proof input absent");
    return value;
  }
}
