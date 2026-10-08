package de.caritas.cob.userservice.api.workflow.accountinactivity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestTemplate;

class AccountInactivityBootstrapTest {
  @Test
  void failedInventoryReleasesGuardAndNextRunRecovers() throws Exception {
    try (var fixture = new Fixture(List.of(person("old", false, "2026-01-01T00:00:00Z")))) {
      fixture.failInventory = true;
      fixture.bootstrap.scan();
      assertThat(fixture.bootstrap.report().complete()).isFalse();
      assertThat(fixture.bootstrap.report().failed()).isEqualTo(1);
      assertThat(fixture.lifecycle.snapshot("old")).isEmpty();
      fixture.failInventory = false;
      var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
      try {
        worker.submit(fixture.bootstrap::scan).get(5, java.util.concurrent.TimeUnit.SECONDS);
      } finally {
        worker.shutdownNow();
      }
      assertThat(fixture.bootstrap.report().complete()).isTrue();
      assertThat(fixture.lifecycle.snapshot("old")).isPresent();
    }
  }

  @Test
  void overlappingInventoriesSerializeRemoteReadsAndPublishOneWholeReport() throws Exception {
    try (var fixture = new Fixture(List.of(person("old", false, "2026-01-01T00:00:00Z")))) {
      fixture.blockInventory = true;
      var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
      try {
        var first = workers.submit(fixture.bootstrap::scan);
        assertThat(fixture.inventoryEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            .isTrue();
        var second = workers.submit(fixture.bootstrap::scan);
        try {
          assertThat(
                  fixture.secondInventoryEntered.await(
                      500, java.util.concurrent.TimeUnit.MILLISECONDS))
              .isFalse();
        } finally {
          fixture.releaseInventory.countDown();
        }
        first.get(5, java.util.concurrent.TimeUnit.SECONDS);
        second.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(fixture.inventoryStarts.get()).isEqualTo(2);
        assertThat(fixture.bootstrap.report().complete()).isTrue();
        assertThat(fixture.lifecycle.snapshot("old").orElseThrow().assignedMonths()).isEqualTo(24);
      } finally {
        fixture.releaseInventory.countDown();
        workers.shutdownNow();
      }
    }
  }

  @Test
  void paginatedInventoryIncludesDormantKeycloakOnlyHumansAndExcludesServiceAccounts()
      throws Exception {
    try (var fixture =
        new Fixture(
            List.of(
                person("old", false, "2026-01-01T00:00:00Z"),
                person("machine", true, "2026-01-01T00:00:00Z")))) {
      fixture.bootstrap.scan();
      var snapshot = fixture.lifecycle.snapshot("old").orElseThrow();
      assertThat(snapshot.assignedMonths()).isEqualTo(24);
      assertThat(snapshot.lastActivity()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
      assertThat(snapshot.dueAt()).isEqualTo(Instant.parse("2028-02-01T00:00:00Z"));
      assertThat(fixture.lifecycle.snapshot("machine")).isEmpty();
      assertThat(fixture.pages.get()).isEqualTo(3);
      assertThat(fixture.bootstrap.report().complete()).isTrue();
    }
  }

  @Test
  void postRolloutMissingSnapshotsAreReportedWithoutApplyingCurrentDefaults() throws Exception {
    try (var fixture = new Fixture(List.of(person("new", false, "2026-02-02T00:00:00Z")))) {
      fixture.bootstrap.scan();
      assertThat(fixture.lifecycle.snapshot("new")).isEmpty();
      assertThat(fixture.bootstrap.report().complete()).isFalse();
      assertThat(fixture.bootstrap.report().missingNew()).isEqualTo(1);
      assertThat(fixture.bootstrap.issues("", 10).getFirst().reason())
          .isEqualTo("POST_ROLLOUT_SNAPSHOT_MISSING");
      fixture.bootstrap.scan();
      assertThat(fixture.bootstrap.report().cutoff())
          .isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
    }
  }

  @Test
  void mixedTechnicalClientPrivilegesRemainHumanWhilePureMachineRolesAreExcluded()
      throws Exception {
    try (var fixture =
        new Fixture(
            List.of(
                person("technical", false, "2026-01-01T00:00:00Z"),
                person("mixed", false, "2026-01-01T00:00:00Z")))) {
      fixture.withClients.set(true);
      fixture.realmRoles.put("technical", "[{\"name\":\"technical\"}]");
      fixture.realmRoles.put("mixed", "[{\"name\":\"technical\"}]");
      fixture.clientRoles.put("mixed", "[{\"name\":\"user-admin\"}]");
      fixture.bootstrap.scan();
      assertThat(fixture.lifecycle.snapshot("technical")).isEmpty();
      assertThat(fixture.lifecycle.snapshot("mixed")).isPresent();
      assertThat(fixture.bootstrap.report().complete()).isTrue();
    }
  }

  static String person(String id, boolean service, String created) {
    return "{\"id\":\""
        + id
        + "\",\"username\":\""
        + id
        + "\",\"createdTimestamp\":"
        + Instant.parse(created).toEpochMilli()
        + ",\"attributes\":{\"tenantId\":[\"0\"]}"
        + (service ? ",\"serviceAccountClientId\":\"worker\"" : "")
        + "}";
  }

  static class Fixture implements AutoCloseable {
    final java.util.Map<String, String> realmRoles = new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.Map<String, String> clientRoles =
        new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.concurrent.atomic.AtomicBoolean withClients =
        new java.util.concurrent.atomic.AtomicBoolean();
    final AtomicInteger inventoryStarts = new AtomicInteger();
    final java.util.concurrent.CountDownLatch inventoryEntered =
        new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.CountDownLatch secondInventoryEntered =
        new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.CountDownLatch releaseInventory =
        new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.ExecutorService httpWorkers =
        java.util.concurrent.Executors.newCachedThreadPool();
    volatile boolean blockInventory;
    volatile boolean failInventory;
    final AtomicInteger pages = new AtomicInteger();
    final java.util.concurrent.atomic.AtomicReference<Throwable> httpFailure =
        new java.util.concurrent.atomic.AtomicReference<>();
    final HttpServer server;
    final AccountInactivityService lifecycle;
    final AccountInactivityBootstrap bootstrap;

    Fixture(List<String> people) throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(httpWorkers);
      server.createContext(
          "/",
          exchange -> {
            try {
              var json = new com.fasterxml.jackson.databind.ObjectMapper();
              var request = json.readTree(exchange.getRequestBody().readAllBytes());
              var proof =
                  com.nimbusds.jose.JWSObject.parse(
                      exchange.getRequestHeaders().getFirst("X-ORISO-Origin-Authorization"));
              if (!proof.verify(
                  new com.nimbusds.jose.crypto.MACVerifier(
                      "different-test-only-origin-key-32".getBytes(StandardCharsets.UTF_8))))
                throw new AssertionError("Invalid inventory origin signature");
              assertThat(exchange.getRequestMethod()).isEqualTo("POST");
              assertThat(exchange.getRequestURI().getPath())
                  .isEqualTo("/realms/test/oriso-commands/v1/account-inventory");
              assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                  .isEqualTo("Bearer bounded-maintenance-token");
              assertThat(request.get("cutoff").asText()).isEqualTo("2026-02-01T00:00:00Z");
              int first = request.get("first").asInt();
              int max = request.get("max").asInt();
              assertThat(max).isEqualTo(1);
              assertThat(proof.getPayload().toJSONObject())
                  .containsEntry("operation", "account.inventory")
                  .containsEntry("originKind", "LIFECYCLE")
                  .containsEntry("roles", List.of())
                  .containsEntry(
                      "target", "cutoff:2026-02-01T00:00:00Z/first:" + first + "/max:" + max);
              int status = failInventory ? 503 : 200;
              if (first == 0) {
                int run = inventoryStarts.incrementAndGet();
                if (run == 1) inventoryEntered.countDown();
                else secondInventoryEntered.countDown();
                if (blockInventory && run == 1) {
                  try {
                    releaseInventory.await(5, java.util.concurrent.TimeUnit.SECONDS);
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                }
              }
              pages.incrementAndGet();
              var accounts = new java.util.ArrayList<java.util.Map<String, Object>>();
              if (first < people.size()) {
                var person = json.readTree(people.get(first));
                String id = person.get("id").asText();
                var effectiveRoles = new java.util.HashSet<String>();
                for (var role :
                    json.readTree(
                        realmRoles.getOrDefault(id, "[{\"name\":\"global-support-admin\"}]")))
                  effectiveRoles.add(role.get("name").asText());
                if (withClients.get())
                  for (var role : json.readTree(clientRoles.getOrDefault(id, "[]")))
                    effectiveRoles.add(role.get("name").asText());
                accounts.add(
                    java.util.Map.of(
                        "id",
                        id,
                        "tenantId",
                        0L,
                        "createdTimestamp",
                        person.get("createdTimestamp").asLong(),
                        "eligibleHuman",
                        !person.has("serviceAccountClientId")
                            && !AccountInactivityIdentityRoles.isPureTechnical(effectiveRoles)));
              }
              String response =
                  json.writeValueAsString(
                      java.util.Map.of("accounts", accounts, "hasMore", first < people.size()));
              var bytes = response.getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(status, bytes.length);
              exchange.getResponseBody().write(bytes);
              exchange.close();
            } catch (Throwable failure) {
              httpFailure.set(failure);
              try {
                exchange.sendResponseHeaders(500, -1);
              } finally {
                exchange.close();
              }
            }
          });
      server.start();
      var ds = new DriverManagerDataSource("jdbc:h2:mem:bootstrap;DB_CLOSE_DELAY=-1", "sa", "");
      var jdbc = new JdbcTemplate(ds);
      jdbc.execute("DROP ALL OBJECTS");
      jdbc.execute(
          "CREATE TABLE account_inactivity(identity_id VARCHAR(36) PRIMARY KEY,tenant_id"
              + " BIGINT,assigned_months INT NOT NULL,revision BIGINT NOT NULL,last_activity"
              + " TIMESTAMP(6) NOT NULL,due_at TIMESTAMP(6) NOT NULL,status VARCHAR(20) NOT"
              + " NULL,last_error VARCHAR(1000),attempts INT DEFAULT 0 NOT NULL)");
      jdbc.execute(
          "CREATE TABLE account_inactivity_rollout(id INT PRIMARY KEY,rollout_at"
              + " TIMESTAMP(6),last_scan TIMESTAMP(6),inventory_complete BOOLEAN DEFAULT"
              + " FALSE,enrolled INT DEFAULT 0,missing_new INT DEFAULT 0,failed INT DEFAULT 0)");
      jdbc.execute(
          "INSERT INTO account_inactivity_rollout(id,rollout_at) VALUES(1,TIMESTAMP '2026-02-01"
              + " 00:00:00')");
      jdbc.execute(
          "CREATE TABLE account_inactivity_bootstrap_issue(identity_id VARCHAR(36) PRIMARY"
              + " KEY,reason VARCHAR(40),observed_at TIMESTAMP(6))");
      var clock = Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
      lifecycle =
          new AccountInactivityService(
              jdbc,
              new DataSourceTransactionManager(ds),
              clock,
              new AccountInactivityEffects() {
                public Set<Role> currentRoles(String id) {
                  return Set.of(Role.OTHER);
                }

                public boolean delete(String id) {
                  throw new AssertionError();
                }

                public boolean suspend(String id) {
                  throw new AssertionError();
                }

                public boolean reactivate(String id) {
                  throw new AssertionError();
                }
              });
      var identities = new de.caritas.cob.userservice.api.config.auth.TaskIdentityConfiguration();
      identities
          .getTasks()
          .put(
              "account-maintenance",
              new de.caritas.cob.userservice.api.config.auth.TaskIdentityCredentials(
                  "backend-account-maintenance", "test-maintenance-secret", "maintenance-subject"));
      var grants =
          org.mockito.Mockito.mock(
              de.caritas.cob.userservice.api.adapters.keycloak.commands.TaskIdentityGrant.class);
      org.mockito.Mockito.when(
              grants.token(
                  de.caritas.cob.userservice.api.config.auth.TaskIdentity.ACCOUNT_MAINTENANCE))
          .thenReturn("bounded-maintenance-token");
      var commands =
          new de.caritas.cob.userservice.api.adapters.keycloak.commands.KeycloakTaskCommands(
              new RestTemplate(new org.springframework.http.client.JdkClientHttpRequestFactory()),
              identities,
              grants,
              new de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityOriginProof(
                  java.util.Base64.getEncoder().encodeToString(new byte[32]),
                  java.util.Base64.getEncoder()
                      .encodeToString(
                          "different-test-only-origin-key-32".getBytes(StandardCharsets.UTF_8)),
                  clock),
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "test");
      bootstrap =
          new AccountInactivityBootstrap(
              jdbc,
              new de.caritas.cob.userservice.api.adapters.keycloak.commands
                  .KeycloakInactivityInventory(jdbc, commands),
              lifecycle,
              clock,
              new DataSourceTransactionManager(ds),
              1);
    }

    public void close() {
      server.stop(0);
      httpWorkers.shutdownNow();
      assertThat(httpFailure.get()).isNull();
    }
  }
}
