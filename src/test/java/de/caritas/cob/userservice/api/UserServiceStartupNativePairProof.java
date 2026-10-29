package de.caritas.cob.userservice.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.ConfigurableWebApplicationContext;

/** Actual application startup. A fenced prefix never establishes a complete application graph. */
class UserServiceStartupNativePairProof {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PREFIX = "de.caritas.cob.userservice.api.";
  private static final String FENCE =
      "Five-actor production activation is gated until native account, lifecycle and coordinated receiver contracts are implemented";
  private static final Set<String> COMMON =
      Set.of(
          "productionClasspath",
          "actualApplication",
          "testingProfile",
          "fiveActors",
          "realPolicies",
          "contextClosed",
          "noUnexpectedFailure");
  private static final Set<String> OPEN =
      Set.of(
          "contextStarted",
          "servletReady",
          "securityReady",
          "jpaReady",
          "scopedOwnersReady",
          "bothSchedulersReady",
          "noLegacyFallback",
          "schemaReady",
          "bootNoEffect",
          "authenticatedMetadata",
          "selfLocaleApplied",
          "selfLocaleReplay",
          "consultantInverseRetention",
          "consultantThenAdmin",
          "adminThenConsultant",
          "pureTenantTwoChildren",
          "invitationRecipientScoped",
          "invitationDraftNoMail",
          "invitationOccupiedNoEffect");

  @Test
  void actualServletApplicationReachesOnlyExpectedProductionStartupState() throws Exception {
    String expected = required("STARTUP_EXPECTED_STATE");
    assertTrue(Set.of("FENCED", "OPEN").contains(expected));
    Map<String, String> catalog =
        JSON.readValue(required("STARTUP_BEAN_CATALOG"), new TypeReference<>() {});
    Set<String> allowedFailures =
        JSON.readValue(required("STARTUP_FAILURE_TYPES"), new TypeReference<>() {});
    var observed = new ConcurrentSkipListSet<String>();
    var witness = new TreeMap<String, Object>();
    witness.put("state", expected);
    var browserWitness = new TreeMap<String, Boolean>();
    for (String key :
        List.of(
            "frontendLocale",
            "ownActorSeed",
            "primaryActorSeed",
            "ownMutationObserved",
            "ownConsultantRemoval",
            "peerAdminRemoval",
            "sourceAndCleanup")) browserWitness.put(key, false);
    witness.put("currentClientBrowser", browserWitness);
    witness.put("invitationNative", Map.of("dispatches", 0, "statuses", List.of()));
    witness.put(
        "tenantNative",
        Map.of(
            "tenant", Map.of("dispatches", 0, "statuses", List.of()),
            "profile", Map.of("dispatches", 0, "statuses", List.of())));
    for (String key : COMMON) witness.put(key, false);
    for (String key : OPEN) witness.put(key, false);
    witness.put("exactFence", false);
    witness.put("observedBeans", List.of());
    witness.put("failureTypes", List.of());
    var context = new AtomicReference<ConfigurableApplicationContext>();
    Throwable startupFailure = null;
    var consultingTypes =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var tenantMetadata =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var tenantMetadataReads = new java.util.concurrent.atomic.AtomicInteger();
    var unexpectedTenantMetadataRequest = new java.util.concurrent.atomic.AtomicBoolean();
    var consultingTypeReads = new java.util.concurrent.atomic.AtomicInteger();
    var unexpectedConsultingTypeRequest = new java.util.concurrent.atomic.AtomicBoolean();
    try {
      consultingTypes.createContext(
          "/",
          exchange -> {
            try {
              if (!"GET".equals(exchange.getRequestMethod())
                  || !"/consultingtypes/basic".equals(exchange.getRequestURI().toString())
                  || exchange.getRequestBody().readNBytes(1).length != 0) {
                unexpectedConsultingTypeRequest.set(true);
                exchange.sendResponseHeaders(403, -1);
                return;
              }
              consultingTypeReads.incrementAndGet();
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.getResponseHeaders().set("Cache-Control", "no-store");
              exchange.sendResponseHeaders(200, 2);
              exchange.getResponseBody().write(new byte[] {'[', ']'});
            } finally {
              exchange.close();
            }
          });
      consultingTypes.start();
      tenantMetadata.createContext(
          "/",
          exchange -> {
            try {
              if (!"GET".equals(exchange.getRequestMethod())
                  || !"/tenant/public/id/22".equals(exchange.getRequestURI().toString())
                  || exchange.getRequestBody().readNBytes(1).length != 0) {
                unexpectedTenantMetadataRequest.set(true);
                exchange.sendResponseHeaders(403, -1);
                return;
              }
              tenantMetadataReads.incrementAndGet();
              byte[] response =
                  "{\"id\":22,\"subdomain\":\"fixture-22\"}"
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.getResponseHeaders().set("Cache-Control", "no-store");
              exchange.sendResponseHeaders(200, response.length);
              exchange.getResponseBody().write(response);
            } finally {
              exchange.close();
            }
          });
      tenantMetadata.start();
      String binary = UserServiceStartupNativePairProof.class.getName().replace('.', '/');
      Path testClasses = Path.of(required("STARTUP_TEST_CLASSES"));
      boolean onlyThisProof;
      try (var files = Files.walk(testClasses)) {
        var classes = files.filter(path -> path.toString().endsWith(".class")).toList();
        onlyThisProof =
            !classes.isEmpty()
                && classes.stream()
                    .allMatch(
                        path -> {
                          String name = testClasses.relativize(path).toString().replace('\\', '/');
                          return name.equals(binary + ".class")
                              || name.equals(
                                  CurrentClientBrowserBridge.class.getName().replace('.', '/')
                                      + ".class")
                              || name.matches(
                                  java.util.regex.Pattern.quote(binary) + "\\$[0-9]+\\.class");
                        });
      }
      boolean actualProduction =
          Path.of(
                  UserServiceApplication.class
                      .getProtectionDomain()
                      .getCodeSource()
                      .getLocation()
                      .toURI())
              .toRealPath()
              .equals(Path.of(required("STARTUP_PRODUCTION_CLASSES")).toRealPath());
      boolean noForeignTestConfig =
          UserServiceStartupNativePairProof.class
                  .getClassLoader()
                  .getResource("de/caritas/cob/userservice/api/testConfig/KeycloakTestConfig.class")
              == null;
      witness.put("productionClasspath", onlyThisProof && actualProduction && noForeignTestConfig);
      assertEquals(
          true,
          witness.get("productionClasspath"),
          "Production classes plus only this diagnostic are required");
      var application = new SpringApplication(UserServiceApplication.class);
      application.setWebApplicationType(WebApplicationType.SERVLET);
      application.setAdditionalProfiles("testing");
      application.setRegisterShutdownHook(false);
      application.setDefaultProperties(
          Map.of(
              "server.port",
              "0",
              "spring.main.banner-mode",
              "off",
              "logging.level.root",
              "OFF",
              "logging.level.org.springframework.web",
              "OFF",
              "spring.sql.init.data-locations",
              "classpath:database/UserServiceDatabase.sql,classpath:database/AccountInactivityDatabase.sql,file:"
                  + required("STARTUP_JOURNAL_FIXTURE")));
      application.addInitializers(
          prepared -> {
            context.set(prepared);
            witness.put("actualApplication", prepared instanceof ConfigurableWebApplicationContext);
            witness.put(
                "testingProfile",
                Arrays.asList(prepared.getEnvironment().getActiveProfiles())
                    .equals(List.of("testing")));
            var environment = prepared.getEnvironment();
            var clients = new HashSet<String>();
            var secrets = new HashSet<String>();
            var subjects = new HashSet<String>();
            for (String actor :
                List.of("SETUP", "ACCOUNT_CREATE", "ACCOUNT_ADMIN", "MAIL", "RUNTIME")) {
              clients.add(environment.getRequiredProperty("IDENTITY_" + actor + "_CLIENT_ID"));
              secrets.add(environment.getRequiredProperty("IDENTITY_" + actor + "_CLIENT_SECRET"));
              subjects.add(
                  environment.getRequiredProperty("IDENTITY_" + actor + "_SERVICE_SUBJECT"));
            }
            witness.put(
                "fiveActors",
                "FIVE_ACTORS".equals(environment.getProperty("TASK_IDENTITY_MODEL"))
                    && clients.size() == 5
                    && secrets.size() == 5
                    && subjects.size() == 5
                    && !clients.contains(
                        environment.getProperty("identity.technical-user.client-id"))
                    && !clients.contains(
                        environment.getProperty("keycloak.config.admin-client-id")));
            witness.put(
                "realPolicies",
                "true".equals(environment.getProperty("ORISO_ACCOUNT_LIFECYCLE_ENABLED"))
                    && !environment
                        .getRequiredProperty("ORISO_ACCOUNT_MAINTENANCE_CONFIG")
                        .isBlank()
                    && !environment
                        .getRequiredProperty("ORISO_PROTECTED_INVITATIONS_CONFIG")
                        .isBlank());
            // Observation only: no getBean, ordering change, replacement, lazy flag or early
            // initialization.
            prepared.addBeanFactoryPostProcessor(
                factory ->
                    factory.addBeanPostProcessor(
                        new BeanPostProcessor() {
                          @Override
                          public Object postProcessAfterInitialization(Object bean, String name) {
                            if (catalog.containsKey(name)) observed.add(name);
                            return bean;
                          }
                        }));
          });
      try {
        var started =
            application.run(
                "--server.port=0",
                "--spring.profiles.active=testing",
                "--tenant.service.api.url=http://127.0.0.1:"
                    + tenantMetadata.getAddress().getPort(),
                "--consulting.type.service.api.url=http://127.0.0.1:"
                    + consultingTypes.getAddress().getPort(),
                "--spring.sql.init.data-locations=classpath:database/UserServiceDatabase.sql,classpath:database/AccountInactivityDatabase.sql,file:"
                    + required("STARTUP_JOURNAL_FIXTURE"));
        witness.put("contextStarted", started.isActive());
        verifyOpen(started, catalog, witness);
        assertTrue(
            consultingTypeReads.get() > 0, "Actual public metadata client must reach the fixture");
        assertFalse(unexpectedConsultingTypeRequest.get());
        assertTrue(
            tenantMetadataReads.get() > 0,
            "Actual tenant response enrichment must reach the fixture");
        assertFalse(unexpectedTenantMetadataRequest.get());
      } catch (RuntimeException failure) {
        startupFailure = failure;
        var types = new ArrayList<String>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable terminal = failure;
        for (Throwable cursor = failure;
            cursor != null && seen.add(cursor);
            cursor = cursor.getCause()) {
          String type = cursor.getClass().getSimpleName();
          types.add(allowedFailures.contains(type) ? type : "OTHER");
          terminal = cursor;
        }
        witness.put("failureTypes", types);
        boolean exact =
            terminal.getClass() == IllegalStateException.class
                && FENCE.equals(terminal.getMessage())
                && Arrays.stream(terminal.getStackTrace())
                    .anyMatch(
                        frame ->
                            frame
                                    .getClassName()
                                    .equals(PREFIX + "config.auth.ServiceIdentitySeparationCheck")
                                && frame.getMethodName().equals("verifyBackendClients"))
                && types.stream()
                    .allMatch(
                        type ->
                            Set.of(
                                    "BeanCreationException",
                                    "UnsatisfiedDependencyException",
                                    "IllegalStateException")
                                .contains(type));
        witness.put("exactFence", exact);
        witness.put("noUnexpectedFailure", exact && expected.equals("FENCED"));
      }
      if (startupFailure == null) witness.put("noUnexpectedFailure", expected.equals("OPEN"));
    } finally {
      var held = context.get();
      if (held != null) {
        held.close();
        witness.put("contextClosed", !held.isActive());
      }
      consultingTypes.stop(0);
      tenantMetadata.stop(0);
      witness.put("observedBeans", List.copyOf(observed));
      Files.writeString(Path.of(required("STARTUP_WITNESS")), JSON.writeValueAsString(witness));
    }
    assertEquals(
        true, witness.get("actualApplication"), "Actual servlet application must be attempted");
    assertEquals(
        true,
        witness.get("noUnexpectedFailure"),
        "Only the exact expected production startup state may pass");
    for (String key : COMMON) assertEquals(true, witness.get(key), key);
    assertEquals(expected.equals("FENCED"), witness.get("exactFence"));
    for (String key : OPEN) assertEquals(expected.equals("OPEN"), witness.get(key), key);
  }

  private static void verifyOpen(
      ConfigurableApplicationContext context,
      Map<String, String> catalog,
      Map<String, Object> witness)
      throws Exception {
    assertInstanceOf(ConfigurableWebApplicationContext.class, context);
    Object webServer = context.getClass().getMethod("getWebServer").invoke(context);
    int port = (int) webServer.getClass().getMethod("getPort").invoke(webServer);
    witness.put(
        "servletReady",
        port > 0 && ((ConfigurableWebApplicationContext) context).getServletContext() != null);
    assertFalse(context.getBeansOfType(SecurityFilterChain.class).isEmpty());
    assertTrue(context.containsBean("springSecurityFilterChain"));
    try (var client = HttpClient.newHttpClient()) {
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/users/data"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      witness.put("securityReady", response.statusCode() == 401);
    }
    var manager = context.getBean(org.springframework.transaction.PlatformTransactionManager.class);
    assertInstanceOf(JpaTransactionManager.class, manager);
    var jpa = (JpaTransactionManager) manager;
    var source = context.getBean(DataSource.class);
    witness.put(
        "jpaReady",
        jpa.getEntityManagerFactory() != null
            && jpa.getEntityManagerFactory().isOpen()
            && jpa.getDataSource() == source);
    for (var entry : catalog.entrySet()) {
      if (Set.of("serviceIdentitySeparationCheck", "entityManagerFactory").contains(entry.getKey()))
        continue;
      String type =
          entry.getValue().startsWith("org.") || entry.getValue().startsWith("com.")
              ? entry.getValue()
              : PREFIX + entry.getValue();
      assertEquals(
          type,
          org.springframework.util.ClassUtils.getUserClass(
                  AopUtils.getTargetClass(context.getBean(entry.getKey())))
              .getName(),
          entry.getKey());
    }
    witness.put("scopedOwnersReady", true);
    Object scheduler = context.getBean("deleteUserAccountScheduler");
    Object asker = context.getBean("scheduledAskerMaintenance");
    Object consultant = context.getBean("scheduledConsultantMaintenance");
    assertSame(asker, ReflectionTestUtils.getField(scheduler, "scopedMaintenance"));
    assertSame(consultant, ReflectionTestUtils.getField(scheduler, "scopedConsultants"));
    for (Object owner : List.of(asker, consultant)) {
      assertNotNull(ReflectionTestUtils.getField(owner, "cleanup"));
      assertNotNull(ReflectionTestUtils.getField(owner, "entities"));
    }
    witness.put("bothSchedulersReady", context.containsBean("accountInactivityScheduler"));
    var identities =
        context.getBean(de.caritas.cob.userservice.api.port.out.IdentityClientConfig.class);
    witness.put(
        "noLegacyFallback",
        identities.isFiveActorMode()
            && AopUtils.getTargetClass(identities)
                .getName()
                .equals(PREFIX + "config.auth.TaskAwareIdentityClientConfig"));
    var jdbc = new JdbcTemplate(source);
    for (String table :
        List.of(
            "user",
            "consultant",
            "admin",
            "account_inactivity",
            "identity_maintenance_intent",
            "identity_lifecycle_attempt",
            "identity_lifecycle_outbox",
            "identity_lifecycle_effect",
            "identity_account_operation_intent",
            "identity_self_password_intent",
            "identity_claimed_password_intent",
            "identity_setup_password_claim",
            "protected_invitation_acceptance",
            "protected_account_creation")) {
      assertNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Long.class));
    }
    assertNotNull(
        jdbc.queryForList("SELECT suspension_source_json FROM account_inactivity WHERE 1=0"));
    assertNotNull(
        jdbc.queryForList("SELECT last_attempt_at FROM identity_maintenance_intent WHERE 1=0"));
    witness.put("schemaReady", true);
    verifySelfHttp(context, port, jdbc, witness);
  }

  private static void verifySelfHttp(
      ConfigurableApplicationContext context,
      int port,
      JdbcTemplate jdbc,
      Map<String, Object> witness)
      throws Exception {
    var readiness =
        context.getBean(
            de.caritas.cob.userservice.api.config.auth.ServiceIdentitySeparationCheck.class);
    assertEquals(Boolean.TRUE, readiness.scopedAccountCommands());
    Path exchange = Path.of(required("STARTUP_PRIVATE_EXCHANGE"));
    Files.writeString(exchange.resolve("boot-ready"), "BOOT_READY");
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
    while (!Files.exists(exchange.resolve("boot-verified.json"))) {
      if (System.nanoTime() >= deadline) fail("Native boot no-effect verification not received");
      Thread.sleep(50);
    }
    var fixture = JSON.readTree(Files.readString(exchange.resolve("boot-verified.json")));
    assertTrue(fixture.path("bootNoEffect").asBoolean(false));
    witness.put("bootNoEffect", true);
    String account = fixture.path("account").asText();
    assertEquals(account, UUID.fromString(account).toString());
    var user =
        new de.caritas.cob.userservice.api.model.User(
            account,
            null,
            fixture.path("username").asText(),
            fixture.path("email").asText(),
            false);
    user.setTenantId(11L);
    user.setLanguageCode(com.neovisionaries.i18n.LanguageCode.en);
    user.setEncourage2fa(false);
    user.setDeletionLifecycleState(
        de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState.ACTIVE);
    context.getBean(de.caritas.cob.userservice.api.port.out.UserRepository.class).save(user);
    seedInactivity(context, account, 11L, fixture.path("nativeCreation").asLong());
    String token = fixture.path("bearer").asText();
    assertFalse(token.isBlank());
    try (var browser =
            CurrentClientBrowserBridge.required()
                ? new CurrentClientBrowserBridge(port, fixture)
                : null;
        var client = HttpClient.newHttpClient()) {
      if (browser != null) browser.seed("frontend", "self", fixture.path("selfTokens"));
      var metadata =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/users/data"))
                  .header("Authorization", "Bearer " + token)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(
          200, metadata.statusCode(), "Authenticated actual GET must reach scoped metadata");
      var body = JSON.readTree(metadata.body());
      assertEquals(account, body.path("userId").asText());
      assertTrue(body.path("scopedAccountCommands").isBoolean());
      assertTrue(body.path("scopedAccountCommands").booleanValue());
      witness.put("authenticatedMetadata", true);
      String operation = UUID.randomUUID().toString();
      var patch =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/users/data"))
              .header("Authorization", "Bearer " + token)
              .header("Idempotency-Key", operation)
              .header("Content-Type", "application/json")
              .method(
                  "PATCH", HttpRequest.BodyPublishers.ofString("{\"preferredLanguage\":\"de\"}"))
              .build();
      if (browser == null) {
        var changed = client.send(patch, HttpResponse.BodyHandlers.discarding());
        assertEquals(204, changed.statusCode(), "Actual scoped SELF locale command must succeed");
      } else {
        browserRecovery(
            browser,
            "frontend",
            "self",
            "locale",
            account,
            operation,
            Map.of("preferredLanguage", "de"),
            "PATCH",
            "/users/data",
            204,
            false,
            witness);
        browserWitness(witness).put("frontendLocale", true);
      }
      assertEquals(
          "de",
          jdbc.queryForObject(
              "select language_code from `user` where user_id=?", String.class, account));
      var original =
          jdbc.queryForMap(
              "select * from identity_account_operation_intent where operation_id=?", operation);
      assertEquals("ACKNOWLEDGED", original.get("status"));
      witness.put("selfLocaleApplied", true);
      if (browser == null)
        assertEquals(204, client.send(patch, HttpResponse.BodyHandlers.discarding()).statusCode());
      assertEquals(
          original,
          jdbc.queryForMap(
              "select * from identity_account_operation_intent where operation_id=?", operation));
      witness.put("selfLocaleReplay", true);
      verifyAccountJourneys(context, port, jdbc, fixture, client, browser, witness);
    }
    if (CurrentClientBrowserBridge.required())
      browserWitness(witness).put("sourceAndCleanup", true);
  }

  private static void verifyAccountJourneys(
      ConfigurableApplicationContext context,
      int port,
      JdbcTemplate jdbc,
      com.fasterxml.jackson.databind.JsonNode fixture,
      HttpClient client,
      CurrentClientBrowserBridge browser,
      Map<String, Object> witness)
      throws Exception {
    var emf = context.getBean(jakarta.persistence.EntityManagerFactory.class);
    seedAdministrator(emf, fixture.path("primary"), "SUPER", false);
    for (String key : List.of("consultantThenAdmin", "adminThenConsultant", "retainedAdmin"))
      seedAdministrator(emf, fixture.path(key), "AGENCY", true);
    seedAdministrator(emf, fixture.path("pureTenant"), "TENANT", false);
    for (String key :
        List.of(
            "primary",
            "consultantThenAdmin",
            "adminThenConsultant",
            "retainedAdmin",
            "pureTenant")) {
      var nativeUser = fixture.path(key);
      seedInactivity(
          context,
          nativeUser.path("id").asText(),
          Long.parseLong(nativeUser.path("attributes").path("tenantId").path(0).asText()),
          nativeUser.path("createdTimestamp").asLong());
    }
    List<Map<String, Object>> supportRows = List.of();
    List<Map<String, Object>> supportRelations = List.of();
    if (browser != null) {
      var support = fixture.path("supportConsultant");
      seedAdministrator(emf, support, null, true);
      seedInactivity(
          context, support.path("id").asText(), 11L, support.path("createdTimestamp").asLong());
      supportRows =
          jdbc.queryForList(
              "select * from consultant where consultant_id=?", support.path("id").asText());
      supportRelations =
          jdbc.queryForList(
              "select * from consultant_agency where consultant_id=? order by id",
              support.path("id").asText());
      assertEquals(
          4,
          jdbc.queryForObject(
              "select count(*) from consultant_agency where agency_id=70 and delete_date is null",
              Integer.class));
      browser.seed("admin", "own", fixture.path("ownTokens"));
      browserWitness(witness).put("ownActorSeed", true);
      browser.seed("admin", "primary", fixture.path("primaryTokens"));
      browserWitness(witness).put("primaryActorSeed", true);
    }
    String bearer = fixture.path("primaryBearer").asText();
    verifyInvitationDraft(context, port, jdbc, fixture, client, bearer, witness);
    var scheduler =
        context.getBean(
            de.caritas.cob.userservice.api.workflow.delete.service.ScheduledConsultantMaintenance
                .class);
    String first = fixture.path("consultantThenAdmin").path("id").asText();
    var firstAdmin = jdbc.queryForMap("select * from admin where admin_id=?", first);
    var firstConsultant = jdbc.queryForMap("select * from consultant where consultant_id=?", first);
    String consultantOperation = UUID.randomUUID().toString();
    if (browser == null)
      command(
          client, port, bearer, "DELETE", consultantPath(first), consultantOperation, null, 200);
    else {
      browserRecovery(
          browser,
          "admin",
          "own",
          "consultant-delete",
          first,
          consultantOperation,
          Map.of("forceDeleteSessions", true),
          "DELETE",
          consultantPath(first),
          200,
          true,
          witness);
      browserWitness(witness).put("ownConsultantRemoval", true);
    }
    assertEquals(firstAdmin, jdbc.queryForMap("select * from admin where admin_id=?", first));
    var retained = jdbc.queryForMap("select * from consultant where consultant_id=?", first);
    assertEquals(firstConsultant.get("create_date"), retained.get("create_date"));
    assertNotNull(retained.get("delete_date"));
    if (browser == null)
      command(
          client, port, bearer, "DELETE", consultantPath(first), consultantOperation, null, 200);
    String adminOperation = UUID.randomUUID().toString();
    if (browser == null)
      command(client, port, bearer, "DELETE", adminPath(first), adminOperation, null, 200);
    else {
      browserRecovery(
          browser,
          "admin",
          "primary",
          "admin-delete",
          first,
          adminOperation,
          Map.of("forceDeleteSessions", true),
          "DELETE",
          adminPath(first),
          200,
          false,
          witness);
      browserWitness(witness).put("peerAdminRemoval", true);
    }
    assertEquals(
        retained, jdbc.queryForMap("select * from consultant where consultant_id=?", first));
    if (browser == null)
      command(client, port, bearer, "DELETE", adminPath(first), adminOperation, null, 200);
    scheduler.performDeletionWorkflow();
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from consultant where consultant_id=?", Integer.class, first));
    command(client, port, bearer, "DELETE", adminPath(first), adminOperation, null, 200);
    witness.put("consultantThenAdmin", true);

    String second = fixture.path("adminThenConsultant").path("id").asText();
    var secondConsultant =
        jdbc.queryForMap("select * from consultant where consultant_id=?", second);
    String secondAdminOperation = UUID.randomUUID().toString();
    command(client, port, bearer, "DELETE", adminPath(second), secondAdminOperation, null, 200);
    assertEquals(
        secondConsultant,
        jdbc.queryForMap("select * from consultant where consultant_id=?", second));
    command(client, port, bearer, "DELETE", adminPath(second), secondAdminOperation, null, 200);
    String secondConsultantOperation = UUID.randomUUID().toString();
    command(
        client,
        port,
        bearer,
        "DELETE",
        consultantPath(second),
        secondConsultantOperation,
        null,
        200);
    command(
        client,
        port,
        bearer,
        "DELETE",
        consultantPath(second),
        secondConsultantOperation,
        null,
        200);
    scheduler.performDeletionWorkflow();
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from consultant where consultant_id=?", Integer.class, second));
    assertEquals(
        0,
        jdbc.queryForObject("select count(*) from admin where admin_id=?", Integer.class, second));
    witness.put("adminThenConsultant", true);

    String third = fixture.path("retainedAdmin").path("id").asText();
    var thirdAdmin = jdbc.queryForMap("select * from admin where admin_id=?", third);
    var thirdRelations =
        jdbc.queryForList("select * from admin_agency where admin_id=? order by id", third);
    String thirdOperation = UUID.randomUUID().toString();
    command(client, port, bearer, "DELETE", consultantPath(third), thirdOperation, null, 200);
    command(client, port, bearer, "DELETE", consultantPath(third), thirdOperation, null, 200);
    scheduler.performDeletionWorkflow();
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from consultant where consultant_id=?", Integer.class, third));
    assertEquals(thirdAdmin, jdbc.queryForMap("select * from admin where admin_id=?", third));
    assertEquals(
        thirdRelations,
        jdbc.queryForList("select * from admin_agency where admin_id=? order by id", third));
    var cleanup =
        jdbc.queryForMap(
            "select * from identity_maintenance_intent where subject_type='CONSULTANT' and subject_id=?",
            third);
    assertEquals("APPLIED", cleanup.get("status"));
    scheduler.performDeletionWorkflow();
    assertEquals(
        cleanup,
        jdbc.queryForMap(
            "select * from identity_maintenance_intent where subject_type='CONSULTANT' and subject_id=?",
            third));
    assertEquals(thirdAdmin, jdbc.queryForMap("select * from admin where admin_id=?", third));
    witness.put("consultantInverseRetention", true);

    String tenant = fixture.path("pureTenant").path("id").asText();
    String tenantOperation = UUID.randomUUID().toString();
    String body =
        "{\"tenantId\":22,\"firstname\":\"Grace\",\"lastname\":\"Hopper\",\"email\":\"grace@fixture.invalid\"}";
    var nativeHttp =
        context.getBean("keycloakRestTemplate", org.springframework.web.client.RestTemplate.class);
    var originalInterceptors = new ArrayList<>(nativeHttp.getInterceptors());
    var dropped = new java.util.concurrent.atomic.AtomicBoolean();
    var tenantBodies = new ArrayList<String>();
    var profileBodies = new ArrayList<String>();
    var tenantStatuses = new ArrayList<Integer>();
    var profileStatuses = new ArrayList<Integer>();
    nativeHttp
        .getInterceptors()
        .add(
            (request, bytes, execution) -> {
              String path = request.getURI().getPath();
              if (path.endsWith("/reassign-tenant"))
                tenantBodies.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
              if (path.endsWith("/profile"))
                profileBodies.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
              var response = execution.execute(request, bytes);
              if (path.endsWith("/reassign-tenant"))
                tenantStatuses.add(response.getStatusCode().value());
              if (path.endsWith("/profile")) profileStatuses.add(response.getStatusCode().value());
              if (path.endsWith("/reassign-tenant")
                  && response.getStatusCode().value() == 200
                  && dropped.compareAndSet(false, true)) {
                response.close();
                throw new java.io.IOException(
                    "Disposable fixture lost successful native tenant reply");
              }
              return response;
            });
    try {
      command(
          client,
          port,
          bearer,
          "PUT",
          "/useradmin/tenantadmins/" + tenant,
          tenantOperation,
          body,
          503);
      assertTrue(dropped.get());
      assertEquals(
          17L,
          jdbc.queryForObject("select tenant_id from admin where admin_id=?", Long.class, tenant));
      var pending =
          jdbc.queryForMap(
              "select * from general_admin_identity_intent where operation_id=?", tenantOperation);
      assertEquals("FROZEN", pending.get("status"));
      var tenantResponse =
          command(
              client,
              port,
              bearer,
              "PUT",
              "/useradmin/tenantadmins/" + tenant,
              tenantOperation,
              body,
              200);
      assertEquals(
          "fixture-22",
          JSON.readTree(tenantResponse).path("_embedded").path("tenantSubdomain").asText());
      // Lost first reply, original retry, then authenticated replay before the profile child.
      assertEquals(3, tenantBodies.size());
      for (String tenantBody : tenantBodies) assertEquals(tenantBodies.getFirst(), tenantBody);
      assertEquals(1, profileBodies.size());
      for (boolean profile : List.of(false, true)) {
        String child =
            UUID.nameUUIDFromBytes(
                    ("tenant-admin-edit/v1/"
                            + tenantOperation
                            + "/"
                            + (profile ? "profile" : "tenant"))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
        var sent = JSON.readTree((profile ? profileBodies : tenantBodies).get(0));
        assertEquals(child, sent.path("operationId").asText());
      }
    } finally {
      nativeHttp.setInterceptors(originalInterceptors);
      witness.put(
          "tenantNative",
          Map.of(
              "tenant",
                  Map.of(
                      "dispatches", tenantBodies.size(), "statuses", List.copyOf(tenantStatuses)),
              "profile",
                  Map.of(
                      "dispatches",
                      profileBodies.size(),
                      "statuses",
                      List.copyOf(profileStatuses))));
    }

    var parent =
        jdbc.queryForMap(
            "select * from general_admin_identity_intent where operation_id=?", tenantOperation);
    assertEquals("APPLIED", parent.get("status"));
    var nativeReceipts = JSON.readTree((String) parent.get("native_receipt"));
    assertFalse(nativeReceipts.path("tenant").asText().isBlank());
    assertFalse(nativeReceipts.path("profile").asText().isBlank());
    var moved = jdbc.queryForMap("select * from admin where admin_id=?", tenant);
    assertEquals(22L, ((Number) moved.get("tenant_id")).longValue());
    assertEquals("Grace", moved.get("first_name"));
    assertEquals("Hopper", moved.get("last_name"));
    assertEquals("grace@fixture.invalid", moved.get("email"));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from consultant where consultant_id=?", Integer.class, tenant));
    command(
        client,
        port,
        bearer,
        "PUT",
        "/useradmin/tenantadmins/" + tenant,
        tenantOperation,
        body,
        200);
    assertEquals(moved, jdbc.queryForMap("select * from admin where admin_id=?", tenant));
    witness.put("pureTenantTwoChildren", true);
    if (browser != null) {
      String support = fixture.path("supportConsultant").path("id").asText();
      assertEquals(
          supportRows,
          jdbc.queryForList("select * from consultant where consultant_id=?", support));
      assertEquals(
          supportRelations,
          jdbc.queryForList(
              "select * from consultant_agency where consultant_id=? order by id", support));
      assertEquals(
          0,
          jdbc.queryForObject(
              "select count(*) from admin where admin_id=?", Integer.class, support));
    }
  }

  /** Actual servlet, production scoped reader and native endpoint; no template or mail dispatch. */
  private static void verifyInvitationDraft(
      ConfigurableApplicationContext context,
      int port,
      JdbcTemplate jdbc,
      com.fasterxml.jackson.databind.JsonNode fixture,
      HttpClient client,
      String bearer,
      Map<String, Object> witness)
      throws Exception {
    var reader =
        context.getBean(
            de.caritas.cob.userservice.api.port.out.InvitationRecipientAvailability.class);
    assertEquals(
        PREFIX + "adapters.keycloak.maintenance.KeycloakInvitationRecipientAvailability",
        AopUtils.getTargetClass(reader).getName());
    witness.put("invitationRecipientScoped", true);
    var keycloak =
        context.getBean(
            de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig.class);
    String server =
        context
            .getEnvironment()
            .getProperty("ORISO_IDENTITY_INTERNAL_AUTH_SERVER_URL", keycloak.getAuthServerUrl());
    var endpoint =
        org.springframework.web.util.UriComponentsBuilder.fromUriString(server)
            .pathSegment(
                "realms",
                keycloak.getRealm(),
                "oriso-account-maintenance",
                "v1",
                "availability",
                "invitation-email")
            .build()
            .encode()
            .toUri();
    var http =
        context.getBean("keycloakRestTemplate", org.springframework.web.client.RestTemplate.class);
    var originalInterceptors = new ArrayList<>(http.getInterceptors());
    var dispatches = new java.util.concurrent.atomic.AtomicInteger();
    var statuses = new ArrayList<Integer>();
    var observers = new ArrayList<>(originalInterceptors);
    observers.add(
        (request, body, execution) -> {
          if (request.getMethod() != org.springframework.http.HttpMethod.POST
              || !endpoint.equals(request.getURI())) return execution.execute(request, body);
          dispatches.incrementAndGet();
          var response = execution.execute(request, body);
          statuses.add(response.getStatusCode().value());
          return response;
        });
    var untouched = invitationEffects(jdbc);
    var beforeInvites = jdbc.queryForList("select * from account_invite order by id");
    String email = "startup-invite@fixture.invalid";
    String occupied = fixture.path("primary").path("email").asText();
    assertTrue(!occupied.isBlank() && !occupied.equals(email), "Existing synthetic owner required");
    assertTrue(
        beforeInvites.stream()
            .noneMatch(
                row ->
                    email.equals(row.get("recipient_email"))
                        || occupied.equals(row.get("recipient_email"))),
        "Native recipient checks must not be satisfied by a pre-existing local claim");
    http.setInterceptors(observers);
    try {
      var created = invitationRequest(client, port, bearer, email);
      assertEquals(201, created.statusCode(), "Actual no-mail invitation create");
      var afterCreate = jdbc.queryForList("select * from account_invite order by id");
      assertTrue(
          afterCreate.size() == beforeInvites.size() + 1 && afterCreate.containsAll(beforeInvites),
          "Only one new invitation row is permitted");
      var rows =
          afterCreate.stream().filter(row -> email.equals(row.get("recipient_email"))).toList();
      assertEquals(1, rows.size());
      var row = rows.getFirst();
      for (String key :
          List.of(
              "target_role",
              "purpose",
              "tenant_id",
              "status",
              "provisioning_status",
              "active_recipient_key",
              "created_by_user_id",
              "expires_at"))
        assertTrue(row.containsKey(key), "Required invitation SQL field " + key);
      assertTrue(
          "PLATFORM_ADMIN".equals(row.get("target_role"))
              && "INVITE".equals(row.get("purpose"))
              && row.get("tenant_id") instanceof Number tenant
              && tenant.longValue() == 0
              && "DRAFT".equals(row.get("status"))
              && "PENDING".equals(row.get("provisioning_status"))
              && email.equals(row.get("active_recipient_key"))
              && fixture.path("primary").path("id").asText().equals(row.get("created_by_user_id"))
              && row.get("expires_at") != null,
          "Exact pending platform invitation SQL state required");
      for (String key :
          List.of(
              "token_hash",
              "tenant_id_reservation_token",
              "tenant_id_allocation_mode",
              "agency_id",
              "agency_id_allocation_mode",
              "department_id",
              "queued_template_id",
              "waiting_for_unit",
              "provisioned_user_id",
              "initial_password_verifier",
              "totp_pending_secret",
              "accepted_at",
              "accepted_by_user_id",
              "unit_created_at",
              "dpa_signed_at",
              "dpa_forwarded_at",
              "superseded_by_invite_id",
              "revoked_at"))
        assertTrue(
            row.containsKey(key) && row.get(key) == null,
            "Draft must not allocate or populate protected field " + key);
      assertTrue(
          untouched.equals(invitationEffects(jdbc)),
          "No delivery, approval, outbox, lifecycle intent or reservation state may change");
      witness.put("invitationDraftNoMail", true);
      var refused = invitationRequest(client, port, bearer, occupied);
      assertEquals(409, refused.statusCode(), "Existing native email owner must refuse the draft");
      assertEquals("EMAIL_NOT_AVAILABLE", refused.headers().firstValue("X-Reason").orElse(null));
      assertTrue(
          afterCreate.equals(jdbc.queryForList("select * from account_invite order by id"))
              && untouched.equals(invitationEffects(jdbc)),
          "Occupied native recipient must leave all SQL rows unchanged");
      assertTrue(
          dispatches.get() == 2 && statuses.equals(List.of(200, 200)),
          "Both actual servlet paths require the fixed native boolean read");
      witness.put("invitationOccupiedNoEffect", true);
    } finally {
      http.setInterceptors(originalInterceptors);
      assertTrue(
          http.getInterceptors().equals(originalInterceptors),
          "Restore every original HTTP interceptor");
      witness.put(
          "invitationNative",
          Map.of("dispatches", dispatches.get(), "statuses", List.copyOf(statuses)));
    }
  }

  private static HttpResponse<Void> invitationRequest(
      HttpClient client, int port, String bearer, String email) throws Exception {
    String body =
        JSON.writeValueAsString(
            Map.of(
                "targetRole",
                "PLATFORM_ADMIN",
                "recipientEmail",
                email,
                "firstName",
                "Invite",
                "lastName",
                "Probe",
                "expiresInDays",
                1));
    return client.send(
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/useradmin/account-invites"))
            .header("Authorization", "Bearer " + bearer)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.discarding());
  }

  private static Map<String, List<Map<String, Object>>> invitationEffects(JdbcTemplate jdbc) {
    var rows = new TreeMap<String, List<Map<String, Object>>>();
    for (String table :
        List.of(
            "invite_email_delivery",
            "protected_invitation_authorization",
            "protected_invitation_mail_outbox",
            "protected_invitation_acceptance",
            "protected_account_creation",
            "identity_lifecycle_attempt",
            "identity_lifecycle_outbox",
            "identity_lifecycle_effect",
            "identity_account_operation_intent",
            "id_reservation_lock",
            "id_reservation_release_task"))
      rows.put(table, jdbc.queryForList("select * from " + table + " order by 1"));
    return rows;
  }

  private static String consultantPath(String account) {
    return "/useradmin/consultants/" + account + "?forceDeleteSessions=true";
  }

  private static String adminPath(String account) {
    return "/useradmin/agencyadmins/" + account;
  }

  private static String command(
      HttpClient client,
      int port,
      String bearer,
      String method,
      String path,
      String operation,
      String body,
      int expected)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + bearer)
            .header("Idempotency-Key", operation);
    if (body != null) request.header("Content-Type", "application/json");
    var response =
        client.send(
            request
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(expected, response.statusCode(), "Actual public " + method + " account command");
    return response.body();
  }

  private static void seedInactivity(
      ConfigurableApplicationContext context, String account, Long tenant, long nativeCreation) {
    assertTrue(nativeCreation > 0, "Original native fixture creation time required");
    var lifecycle =
        context.getBean(
            de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService
                .class);
    assertTrue(lifecycle.snapshot(account).isEmpty());
    var creation = java.time.Instant.ofEpochMilli(nativeCreation);
    lifecycle.assignAtCreation(account, tenant, 18, 1, creation);
    var row = lifecycle.snapshot(account).orElseThrow();
    assertEquals(tenant, row.tenantId());
    assertEquals(18, row.assignedMonths());
    assertEquals(1, row.revision());
    assertEquals(creation, row.lastActivity());
    assertEquals(
        de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService.Status
            .ACTIVE,
        row.status());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Boolean> browserWitness(Map<String, Object> witness) {
    return (Map<String, Boolean>) witness.get("currentClientBrowser");
  }

  private static void recordOwnMutation(
      Map<String, Object> witness,
      boolean own,
      List<com.fasterxml.jackson.databind.JsonNode> observations) {
    if (own
        && observations.stream()
            .anyMatch(
                item ->
                    "DELETE".equals(item.path("method").asText())
                        && item.path("status").asInt() == 200))
      browserWitness(witness).put("ownMutationObserved", true);
  }

  private static void browserRecovery(
      CurrentClientBrowserBridge browser,
      String client,
      String actor,
      String kind,
      String target,
      String operation,
      Map<String, Object> payload,
      String method,
      String path,
      int status,
      boolean own,
      Map<String, Object> witness)
      throws Exception {
    var observations = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    var result = browser.send(client, actor, kind, target, operation, payload, true);
    result.path("requests").forEach(observations::add);
    recordOwnMutation(witness, own, observations);
    int refreshes = result.path("refreshes").asInt();
    assertTrue(
        result.path("originalIntentOnly").asBoolean(false), "Browser retained the original intent");
    if (!result.path("ok").asBoolean(false)) {
      assertFalse(
          result.path("acknowledged").asBoolean(false),
          "Known success must never be replayed to repair a later postcondition");
      result = browser.send(client, actor, kind, target, operation, payload, false);
      result.path("requests").forEach(observations::add);
      recordOwnMutation(witness, own, observations);
      refreshes += result.path("refreshes").asInt();
    }
    assertTrue(
        result.path("ok").asBoolean(false)
            && result.path("acknowledged").asBoolean(false)
            && result.path("postconditions").asBoolean(false)
            && result.path("originalIntentOnly").asBoolean(false),
        "Actual browser must acknowledge the original operation and its postconditions");
    var mutations =
        observations.stream().filter(item -> !"GET".equals(item.path("method").asText())).toList();
    assertTrue(
        mutations.size() == 2 || (own && mutations.size() == 3),
        "Bounded original browser recovery requests required");
    var statuses = mutations.stream().map(item -> item.path("status").asInt()).toList();
    assertTrue(
        statuses.equals(List.of(status, status))
            || own
                && (statuses.equals(List.of(200, 403, 200))
                    || statuses.equals(List.of(200, 503, 200))),
        "Only original success, optional stale-token denial, then original success is accepted");
    assertTrue(
        mutations.getFirst().path("dropped").asBoolean(false),
        "The first successful reply must be the lost reply");
    String body = mutations.getFirst().path("bodySHA256").asText();
    assertTrue(body.matches("[0-9a-f]{64}"), "Captured exact browser request digest required");
    assertTrue(
        mutations.stream()
            .allMatch(
                item ->
                    method.equals(item.path("method").asText())
                        && path.equals(item.path("path").asText())
                        && operation.equals(item.path("operationId").asText())
                        && body.equals(item.path("bodySHA256").asText())
                        && (item.path("status").asInt() == status
                            || own && Set.of(403, 503).contains(item.path("status").asInt()))),
        "Every browser retry must retain the original method, URL, operation and body");
    assertTrue(
        mutations.stream().filter(item -> item.path("status").asInt() == status).count() == 2,
        "Exactly two successful original browser requests required");
    assertTrue(
        mutations.stream().filter(item -> item.path("dropped").asBoolean(false)).count() == 1
            && mutations.stream()
                .filter(item -> item.path("dropped").asBoolean(false))
                .allMatch(item -> item.path("status").asInt() == status),
        "Exactly the first successful upstream reply is lost");
    if (own)
      assertTrue(
          refreshes >= 1
              && result.path("sameHuman").asBoolean(false)
              && result.path("roleRenewed").asBoolean(false)
              && result.path("metadataRead").asBoolean(false),
          "Own removal must renew the same human and read actual surviving Admin metadata");
    if ("frontend".equals(client))
      assertTrue(
          result.path("sameHuman").asBoolean(false) && result.path("metadataRead").asBoolean(false),
          "Frontend must retain the human and read actual metadata");
  }

  private static void seedAdministrator(
      jakarta.persistence.EntityManagerFactory factory,
      com.fasterxml.jackson.databind.JsonNode nativeUser,
      String type,
      boolean combined) {
    var entities = factory.createEntityManager();
    try {
      entities.getTransaction().begin();
      String account = nativeUser.path("id").asText();
      assertEquals(account, UUID.fromString(account).toString());
      long tenant = Long.parseLong(nativeUser.path("attributes").path("tenantId").path(0).asText());
      var now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
      var admin =
          de.caritas.cob.userservice.api.model.Admin.builder()
              .id(account)
              .type(
                  type == null
                      ? null
                      : de.caritas.cob.userservice.api.model.Admin.AdminType.valueOf(type))
              .tenantId(tenant)
              .username(nativeUser.path("username").asText())
              .firstName(nativeUser.path("firstName").asText())
              .lastName(nativeUser.path("lastName").asText())
              .email(nativeUser.path("email").asText())
              .createDate(now)
              .updateDate(now)
              .build();
      if (type != null) entities.persist(admin);
      if (combined) {
        var consultant =
            de.caritas.cob.userservice.api.model.Consultant.builder()
                .id(account)
                .tenantId(tenant)
                .username(
                    new de.caritas.cob.userservice.api.helper.UsernameTranscoder()
                        .encodeUsername(admin.getUsername()))
                .firstName(admin.getFirstName())
                .lastName(admin.getLastName())
                .email(admin.getEmail())
                .languageCode(com.neovisionaries.i18n.LanguageCode.en)
                .status(de.caritas.cob.userservice.api.model.ConsultantStatus.CREATED)
                .encourage2fa(false)
                .magicLinkLoginEnabled(false)
                .notifyEnquiriesRepeating(false)
                .notifyNewChatMessageFromAdviceSeeker(false)
                .createDate(now)
                .updateDate(now)
                .build();
        entities.persist(consultant);
        if (CurrentClientBrowserBridge.required())
          entities.persist(
              de.caritas.cob.userservice.api.model.ConsultantAgency.builder()
                  .consultant(consultant)
                  .agencyId(70L)
                  .tenantId(tenant)
                  .status(de.caritas.cob.userservice.api.model.ConsultantAgencyStatus.CREATED)
                  .createDate(now)
                  .updateDate(now)
                  .build());
        if (type != null)
          entities.persist(
              de.caritas.cob.userservice.api.model.AdminAgency.builder()
                  .admin(admin)
                  .agencyId(70L)
                  .createDate(now)
                  .updateDate(now)
                  .build());
      }
      entities.getTransaction().commit();
    } finally {
      entities.close();
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Required diagnostic input absent");
    return value;
  }
}
