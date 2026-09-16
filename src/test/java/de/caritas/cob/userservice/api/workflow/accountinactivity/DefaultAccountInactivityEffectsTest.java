package de.caritas.cob.userservice.api.workflow.accountinactivity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.workflow.delete.service.InactiveAskerDeletionService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestTemplate;

class DefaultAccountInactivityEffectsTest {
  @Test
  void suspensionAndReactivationPreserveAnExistingMatrixLock() throws Exception {
    try (var remote = new Remote()) {
      var ds =
          new DriverManagerDataSource(
              "jdbc:h2:mem:effects;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER", "sa", "");
      var jdbc = new JdbcTemplate(ds);
      jdbc.execute("DROP ALL OBJECTS");
      jdbc.execute("CREATE TABLE user(user_id VARCHAR(36),matrix_user_id VARCHAR(255))");
      jdbc.execute(
          "CREATE TABLE consultant(consultant_id VARCHAR(36),matrix_user_id VARCHAR(255))");
      jdbc.execute("CREATE TABLE admin(admin_id VARCHAR(36))");
      jdbc.execute("INSERT INTO consultant VALUES('person','@person:example')");
      jdbc.execute(
          "CREATE TABLE account_inactivity_access_state(identity_id VARCHAR(36) PRIMARY"
              + " KEY,keycloak_enabled BOOLEAN NOT NULL,restored BOOLEAN NOT"
              + " NULL,deletion_authorized BOOLEAN NOT NULL)");
      jdbc.execute(
          "CREATE TABLE account_inactivity_matrix_state(identity_id VARCHAR(36),matrix_user_id"
              + " VARCHAR(255),original_locked BOOLEAN NOT NULL,PRIMARY"
              + " KEY(identity_id,matrix_user_id))");
      var kcConfig = new KeycloakConfig();
      kcConfig.setRealm("test");
      try (var kc =
          KeycloakBuilder.builder()
              .serverUrl(remote.url())
              .realm("test")
              .authorization("test-token")
              .build()) {
        var client = new KeycloakClient(new RestTemplate(), kc, kcConfig);
        var matrixConfig = new MatrixConfig();
        matrixConfig.setApiUrl(remote.url());
        matrixConfig.setAdminUsername("admin");
        matrixConfig.setAdminPassword("test-only");
        var matrix =
            new MatrixSynapseService(
                matrixConfig, new RestTemplate(), new RestTemplate(), null, null);
        var effects =
            new DefaultAccountInactivityEffects(
                jdbc,
                new DataSourceTransactionManager(ds),
                client,
                matrix,
                new StaticListableBeanFactory()
                    .getBeanProvider(InactiveAskerDeletionService.class));
        assertThat(effects.suspend("person")).isTrue();
        assertThat(remote.enabled.get()).isFalse();
        assertThat(remote.locked.get()).isTrue();
        assertThat(remote.logouts.get()).isEqualTo(1);
        assertThat(effects.reactivate("person")).isTrue();
        assertThat(remote.enabled.get()).isTrue();
        assertThat(remote.locked.get()).isTrue();
      }
    }
  }

  @Test
  void partialMatrixFailureKeepsOriginalAccessStateAcrossTransactionRollback() throws Exception {
    try (var remote = new Remote()) {
      remote.locked.set(false);
      remote.failMatrix.set(true);
      var ds =
          new DriverManagerDataSource(
              "jdbc:h2:mem:effectsRetry;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER", "sa", "");
      var jdbc = new JdbcTemplate(ds);
      jdbc.execute("DROP ALL OBJECTS");
      jdbc.execute("CREATE TABLE user(user_id VARCHAR(36),matrix_user_id VARCHAR(255))");
      jdbc.execute(
          "CREATE TABLE consultant(consultant_id VARCHAR(36),matrix_user_id VARCHAR(255))");
      jdbc.execute("CREATE TABLE admin(admin_id VARCHAR(36))");
      jdbc.execute("INSERT INTO consultant VALUES('person','@person:example')");
      jdbc.execute(
          "CREATE TABLE account_inactivity_access_state(identity_id VARCHAR(36) PRIMARY"
              + " KEY,keycloak_enabled BOOLEAN NOT NULL,restored BOOLEAN NOT"
              + " NULL,deletion_authorized BOOLEAN NOT NULL)");
      jdbc.execute(
          "CREATE TABLE account_inactivity_matrix_state(identity_id VARCHAR(36),matrix_user_id"
              + " VARCHAR(255),original_locked BOOLEAN NOT NULL,PRIMARY"
              + " KEY(identity_id,matrix_user_id))");
      var kcConfig = new KeycloakConfig();
      kcConfig.setRealm("test");
      try (var kc =
          KeycloakBuilder.builder()
              .serverUrl(remote.url())
              .realm("test")
              .authorization("test-token")
              .build()) {
        var client = new KeycloakClient(new RestTemplate(), kc, kcConfig);
        var matrixConfig = new MatrixConfig();
        matrixConfig.setApiUrl(remote.url());
        matrixConfig.setAdminUsername("admin");
        matrixConfig.setAdminPassword("test-only");
        var matrix =
            new MatrixSynapseService(
                matrixConfig, new RestTemplate(), new RestTemplate(), null, null);
        var effects =
            new DefaultAccountInactivityEffects(
                jdbc,
                new DataSourceTransactionManager(ds),
                client,
                matrix,
                new StaticListableBeanFactory()
                    .getBeanProvider(InactiveAskerDeletionService.class));
        var outer =
            new org.springframework.transaction.support.TransactionTemplate(
                new DataSourceTransactionManager(ds));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> outer.executeWithoutResult(ignored -> effects.suspend("person")))
            .isInstanceOf(AccountInactivityEffectException.class);
        assertThat(remote.enabled.get()).isFalse();
        remote.failMatrix.set(false);
        assertThat(effects.suspend("person")).isTrue();
        assertThat(effects.reactivate("person")).isTrue();
        assertThat(remote.enabled.get()).isTrue();
        assertThat(remote.locked.get()).isFalse();
      }
    }
  }

  @Test
  void finalMixedRoleCheckRefusesDestructiveDeletion() throws Exception {
    try (var remote = new Remote()) {
      remote.roles.set("[{\"name\":\"user\"},{\"name\":\"consultant\"}]");
      var ds =
          new DriverManagerDataSource(
              "jdbc:h2:mem:effects;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER", "sa", "");
      var jdbc = new JdbcTemplate(ds);
      jdbc.execute("DROP ALL OBJECTS");
      jdbc.execute("CREATE TABLE user(user_id VARCHAR(36),matrix_user_id VARCHAR(255))");
      jdbc.execute(
          "CREATE TABLE consultant(consultant_id VARCHAR(36),matrix_user_id VARCHAR(255))");
      jdbc.execute("CREATE TABLE admin(admin_id VARCHAR(36))");
      jdbc.execute("INSERT INTO consultant VALUES('person','@person:example')");
      jdbc.execute(
          "CREATE TABLE account_inactivity_access_state(identity_id VARCHAR(36) PRIMARY"
              + " KEY,keycloak_enabled BOOLEAN NOT NULL,restored BOOLEAN NOT"
              + " NULL,deletion_authorized BOOLEAN NOT NULL)");
      jdbc.execute(
          "CREATE TABLE account_inactivity_matrix_state(identity_id VARCHAR(36),matrix_user_id"
              + " VARCHAR(255),original_locked BOOLEAN NOT NULL,PRIMARY"
              + " KEY(identity_id,matrix_user_id))");
      var kcConfig = new KeycloakConfig();
      kcConfig.setRealm("test");
      try (var kc =
          KeycloakBuilder.builder()
              .serverUrl(remote.url())
              .realm("test")
              .authorization("test-token")
              .build()) {
        var client = new KeycloakClient(new RestTemplate(), kc, kcConfig);
        var matrixConfig = new MatrixConfig();
        matrixConfig.setApiUrl(remote.url());
        matrixConfig.setAdminUsername("admin");
        matrixConfig.setAdminPassword("test-only");
        var matrix =
            new MatrixSynapseService(
                matrixConfig, new RestTemplate(), new RestTemplate(), null, null);
        var effects =
            new DefaultAccountInactivityEffects(
                jdbc,
                new DataSourceTransactionManager(ds),
                client,
                matrix,
                new StaticListableBeanFactory()
                    .getBeanProvider(InactiveAskerDeletionService.class));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> effects.delete("person"))
            .isInstanceOfSatisfying(
                AccountInactivityEffectException.class,
                e ->
                    assertThat(e.code())
                        .isEqualTo(AccountInactivityEffectException.Code.ROLE_CHANGED));
        assertThat(remote.enabled.get()).isTrue();
        assertThat(remote.logouts.get()).isZero();
      }
    }
  }

  @Test
  void localAdminCannotBeDeletedEvenWithOnlyAskerRealmRole() throws Exception {
    try (var remote = new Remote()) {
      remote.roles.set("[{\"name\":\"user\"}]");
      var ds =
          new DriverManagerDataSource(
              "jdbc:h2:mem:effects;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER", "sa", "");
      var jdbc = new JdbcTemplate(ds);
      jdbc.execute("DROP ALL OBJECTS");
      jdbc.execute("CREATE TABLE user(user_id VARCHAR(36),matrix_user_id VARCHAR(255))");
      jdbc.execute(
          "CREATE TABLE consultant(consultant_id VARCHAR(36),matrix_user_id VARCHAR(255))");
      jdbc.execute("CREATE TABLE admin(admin_id VARCHAR(36))");
      jdbc.execute("INSERT INTO admin VALUES('person')");
      jdbc.execute("DELETE FROM consultant");
      jdbc.execute(
          "CREATE TABLE account_inactivity_access_state(identity_id VARCHAR(36) PRIMARY"
              + " KEY,keycloak_enabled BOOLEAN NOT NULL,restored BOOLEAN NOT"
              + " NULL,deletion_authorized BOOLEAN NOT NULL)");
      jdbc.execute(
          "CREATE TABLE account_inactivity_matrix_state(identity_id VARCHAR(36),matrix_user_id"
              + " VARCHAR(255),original_locked BOOLEAN NOT NULL,PRIMARY"
              + " KEY(identity_id,matrix_user_id))");
      var kcConfig = new KeycloakConfig();
      kcConfig.setRealm("test");
      try (var kc =
          KeycloakBuilder.builder()
              .serverUrl(remote.url())
              .realm("test")
              .authorization("test-token")
              .build()) {
        var client = new KeycloakClient(new RestTemplate(), kc, kcConfig);
        var matrixConfig = new MatrixConfig();
        matrixConfig.setApiUrl(remote.url());
        matrixConfig.setAdminUsername("admin");
        matrixConfig.setAdminPassword("test-only");
        var matrix =
            new MatrixSynapseService(
                matrixConfig, new RestTemplate(), new RestTemplate(), null, null);
        var effects =
            new DefaultAccountInactivityEffects(
                jdbc,
                new DataSourceTransactionManager(ds),
                client,
                matrix,
                new StaticListableBeanFactory()
                    .getBeanProvider(InactiveAskerDeletionService.class));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> effects.delete("person"))
            .isInstanceOfSatisfying(
                AccountInactivityEffectException.class,
                e ->
                    assertThat(e.code())
                        .isEqualTo(AccountInactivityEffectException.Code.ROLE_CHANGED));
        assertThat(remote.enabled.get()).isTrue();
        assertThat(remote.logouts.get()).isZero();
      }
    }
  }

  @Test
  void clientAdministratorRoleCannotBeDeletedEvenWithOnlyAskerRealmRole() throws Exception {
    try (var remote = new Remote()) {
      remote.clients.set("[{\"id\":\"app-id\",\"clientId\":\"oriso\"}]");
      remote.clientRoles.set("[{\"name\":\"user-admin\"}]");
      remote.roles.set("[{\"name\":\"user\"}]");
      var ds =
          new DriverManagerDataSource(
              "jdbc:h2:mem:effects;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER", "sa", "");
      var jdbc = new JdbcTemplate(ds);
      jdbc.execute("DROP ALL OBJECTS");
      jdbc.execute("CREATE TABLE user(user_id VARCHAR(36),matrix_user_id VARCHAR(255))");
      jdbc.execute(
          "CREATE TABLE consultant(consultant_id VARCHAR(36),matrix_user_id VARCHAR(255))");
      jdbc.execute("CREATE TABLE admin(admin_id VARCHAR(36))");

      jdbc.execute("DELETE FROM consultant");
      jdbc.execute(
          "CREATE TABLE account_inactivity_access_state(identity_id VARCHAR(36) PRIMARY"
              + " KEY,keycloak_enabled BOOLEAN NOT NULL,restored BOOLEAN NOT"
              + " NULL,deletion_authorized BOOLEAN NOT NULL)");
      jdbc.execute(
          "CREATE TABLE account_inactivity_matrix_state(identity_id VARCHAR(36),matrix_user_id"
              + " VARCHAR(255),original_locked BOOLEAN NOT NULL,PRIMARY"
              + " KEY(identity_id,matrix_user_id))");
      var kcConfig = new KeycloakConfig();
      kcConfig.setRealm("test");
      try (var kc =
          KeycloakBuilder.builder()
              .serverUrl(remote.url())
              .realm("test")
              .authorization("test-token")
              .build()) {
        var client = new KeycloakClient(new RestTemplate(), kc, kcConfig);
        var matrixConfig = new MatrixConfig();
        matrixConfig.setApiUrl(remote.url());
        matrixConfig.setAdminUsername("admin");
        matrixConfig.setAdminPassword("test-only");
        var matrix =
            new MatrixSynapseService(
                matrixConfig, new RestTemplate(), new RestTemplate(), null, null);
        var effects =
            new DefaultAccountInactivityEffects(
                jdbc,
                new DataSourceTransactionManager(ds),
                client,
                matrix,
                new StaticListableBeanFactory()
                    .getBeanProvider(InactiveAskerDeletionService.class));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> effects.delete("person"))
            .isInstanceOfSatisfying(
                AccountInactivityEffectException.class,
                e ->
                    assertThat(e.code())
                        .isEqualTo(AccountInactivityEffectException.Code.ROLE_CHANGED));
        assertThat(remote.enabled.get()).isTrue();
        assertThat(remote.logouts.get()).isZero();
      }
    }
  }

  @Test
  void reactivationPreservesAnAlreadyDisabledKeycloakAccount() throws Exception {
    try (var remote = new Remote()) {
      remote.enabled.set(false);
      var ds =
          new DriverManagerDataSource(
              "jdbc:h2:mem:effects;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER", "sa", "");
      var jdbc = new JdbcTemplate(ds);
      jdbc.execute("DROP ALL OBJECTS");
      jdbc.execute("CREATE TABLE user(user_id VARCHAR(36),matrix_user_id VARCHAR(255))");
      jdbc.execute(
          "CREATE TABLE consultant(consultant_id VARCHAR(36),matrix_user_id VARCHAR(255))");
      jdbc.execute("CREATE TABLE admin(admin_id VARCHAR(36))");
      jdbc.execute("INSERT INTO consultant VALUES('person','@person:example')");
      jdbc.execute(
          "CREATE TABLE account_inactivity_access_state(identity_id VARCHAR(36) PRIMARY"
              + " KEY,keycloak_enabled BOOLEAN NOT NULL,restored BOOLEAN NOT"
              + " NULL,deletion_authorized BOOLEAN NOT NULL)");
      jdbc.execute(
          "CREATE TABLE account_inactivity_matrix_state(identity_id VARCHAR(36),matrix_user_id"
              + " VARCHAR(255),original_locked BOOLEAN NOT NULL,PRIMARY"
              + " KEY(identity_id,matrix_user_id))");
      var kcConfig = new KeycloakConfig();
      kcConfig.setRealm("test");
      try (var kc =
          KeycloakBuilder.builder()
              .serverUrl(remote.url())
              .realm("test")
              .authorization("test-token")
              .build()) {
        var client = new KeycloakClient(new RestTemplate(), kc, kcConfig);
        var matrixConfig = new MatrixConfig();
        matrixConfig.setApiUrl(remote.url());
        matrixConfig.setAdminUsername("admin");
        matrixConfig.setAdminPassword("test-only");
        var matrix =
            new MatrixSynapseService(
                matrixConfig, new RestTemplate(), new RestTemplate(), null, null);
        var effects =
            new DefaultAccountInactivityEffects(
                jdbc,
                new DataSourceTransactionManager(ds),
                client,
                matrix,
                new StaticListableBeanFactory()
                    .getBeanProvider(InactiveAskerDeletionService.class));
        assertThat(effects.suspend("person")).isTrue();
        assertThat(remote.enabled.get()).isFalse();
        assertThat(remote.locked.get()).isTrue();
        assertThat(remote.logouts.get()).isEqualTo(1);
        assertThat(effects.reactivate("person")).isTrue();
        assertThat(remote.enabled.get()).isFalse();
        assertThat(remote.locked.get()).isTrue();
      }
    }
  }

  static class Remote implements AutoCloseable {
    final java.util.concurrent.atomic.AtomicReference<String> roles =
        new java.util.concurrent.atomic.AtomicReference<>("[{\"name\":\"consultant\"}]");
    final java.util.concurrent.atomic.AtomicReference<String> clients =
        new java.util.concurrent.atomic.AtomicReference<>("[]");
    final java.util.concurrent.atomic.AtomicReference<String> clientRoles =
        new java.util.concurrent.atomic.AtomicReference<>("[]");
    final AtomicBoolean enabled = new AtomicBoolean(true);
    final AtomicBoolean locked = new AtomicBoolean(true);
    final AtomicBoolean failMatrix = new AtomicBoolean();
    final AtomicInteger logouts = new AtomicInteger();
    final HttpServer server;

    Remote() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            var path = exchange.getRequestURI().getPath();
            var method = exchange.getRequestMethod();
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = "{}";
            int status = 200;
            if (path.endsWith("/login")) response = "{\"access_token\":\"test-token\"}";
            else if (path.contains("/_synapse/admin/v2/users/")) {
              if (method.equals("PUT") && failMatrix.get()) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
              }
              if (method.equals("PUT"))
                locked.set(body.matches("(?s).*\\\"locked\\\"\\s*:\\s*true.*"));
              response = "{\"locked\":" + locked.get() + "}";
            } else if (path.endsWith("/logout")) {
              logouts.incrementAndGet();
              status = 204;
            } else if (path.endsWith("/role-mappings/realm/composite")) response = roles.get();
            else if (path.endsWith("/clients")) response = clients.get();
            else if (path.endsWith("/role-mappings/clients/app-id/composite"))
              response = clientRoles.get();
            else if (path.endsWith("/sessions")) response = "[]";
            else if (path.endsWith("/users/person")) {
              if (method.equals("PUT")) {
                enabled.set(body.matches("(?s).*\\\"enabled\\\"\\s*:\\s*true.*"));
                status = 204;
              }
              response = "{\"id\":\"person\",\"enabled\":" + enabled.get() + "}";
            } else {
              status = 404;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status == 204) exchange.sendResponseHeaders(status, -1);
            else {
              var bytes = response.getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(status, bytes.length);
              exchange.getResponseBody().write(bytes);
            }
            exchange.close();
          });
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void close() {
      server.stop(0);
    }
  }
}
