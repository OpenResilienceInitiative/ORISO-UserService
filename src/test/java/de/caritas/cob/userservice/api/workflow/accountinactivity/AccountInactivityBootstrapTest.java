package de.caritas.cob.userservice.api.workflow.accountinactivity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.KeycloakBuilder;
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
    final HttpServer server;
    final org.keycloak.admin.client.Keycloak kc;
    final AccountInactivityService lifecycle;
    final AccountInactivityBootstrap bootstrap;

    Fixture(List<String> people) throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(httpWorkers);
      server.createContext(
          "/",
          exchange -> {
            var path = exchange.getRequestURI().getPath();
            String response;
            int status = 200;
            if (path.endsWith("/clients")) {
              if (failInventory) status = 503;
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
            if (path.endsWith("/users")) {
              pages.incrementAndGet();
              var query = exchange.getRequestURI().getQuery();
              int first =
                  Integer.parseInt(
                      java.util.Arrays.stream(query.split("&"))
                          .filter(p -> p.startsWith("first="))
                          .findFirst()
                          .orElse("first=0")
                          .substring(6));
              response = first < people.size() ? "[" + people.get(first) + "]" : "[]";
            } else if (path.endsWith("/clients"))
              response = withClients.get() ? "[{\"id\":\"app\",\"clientId\":\"oriso\"}]" : "[]";
            else if (path.endsWith("/role-mappings/realm/composite"))
              response =
                  realmRoles.getOrDefault(
                      path.split("/users/")[1].split("/")[0],
                      "[{\"name\":\"global-support-admin\"}]");
            else if (path.endsWith("/role-mappings/clients/app/composite"))
              response = clientRoles.getOrDefault(path.split("/users/")[1].split("/")[0], "[]");
            else {
              String id = path.substring(path.lastIndexOf('/') + 1);
              response =
                  people.stream()
                      .filter(p -> p.contains("\"id\":\"" + id + "\""))
                      .findFirst()
                      .orElse("{}");
              if (response.equals("{}")) status = 404;
            }
            var bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
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
      kc =
          KeycloakBuilder.builder()
              .serverUrl("http://127.0.0.1:" + server.getAddress().getPort())
              .realm("test")
              .authorization("test-token")
              .build();
      var config = new KeycloakConfig();
      config.setRealm("test");
      bootstrap =
          new AccountInactivityBootstrap(
              jdbc,
              new KeycloakClient(new RestTemplate(), kc, config),
              lifecycle,
              clock,
              new DataSourceTransactionManager(ds),
              1);
    }

    public void close() {
      kc.close();
      server.stop(0);
      httpWorkers.shutdownNow();
    }
  }
}
