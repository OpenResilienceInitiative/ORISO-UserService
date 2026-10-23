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
          "schemaReady");

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
    for (String key : COMMON) witness.put(key, false);
    for (String key : OPEN) witness.put(key, false);
    witness.put("exactFence", false);
    witness.put("observedBeans", List.of());
    witness.put("failureTypes", List.of());
    var context = new AtomicReference<ConfigurableApplicationContext>();
    Throwable startupFailure = null;
    try {
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
                Arrays.asList(prepared.getEnvironment().getActiveProfiles()).contains("testing"));
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
                "--spring.sql.init.data-locations=classpath:database/UserServiceDatabase.sql,classpath:database/AccountInactivityDatabase.sql,file:"
                    + required("STARTUP_JOURNAL_FIXTURE"));
        witness.put("contextStarted", started.isActive());
        verifyOpen(started, catalog, witness);
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
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/users/data"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.discarding());
    witness.put("securityReady", response.statusCode() == 401);
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
          type, AopUtils.getTargetClass(context.getBean(entry.getKey())).getName(), entry.getKey());
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
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Required diagnostic input absent");
    return value;
  }
}
