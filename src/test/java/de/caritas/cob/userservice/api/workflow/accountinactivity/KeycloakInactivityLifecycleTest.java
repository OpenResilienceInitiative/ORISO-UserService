package de.caritas.cob.userservice.api.workflow.accountinactivity;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;

class KeycloakInactivityLifecycleTest {
  @Test
  void actualDurableWorkflowStateAndRecordedEnabledFlagBoundEveryEffect() throws Exception {
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:h2:mem:lifecycle-authority;DB_CLOSE_DELAY=-1", "sa", ""));
    jdbc.execute("DROP ALL OBJECTS");
    jdbc.execute(
        "CREATE TABLE account_inactivity(identity_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT,status VARCHAR(20))");
    jdbc.execute(
        "CREATE TABLE account_inactivity_access_state(identity_id VARCHAR(36) PRIMARY KEY,keycloak_enabled BOOLEAN,restored BOOLEAN,deletion_authorized BOOLEAN)");
    jdbc.update("INSERT INTO account_inactivity VALUES('owned',7,'ACTIVE')");
    var calls = new AtomicInteger();
    var httpFailure = new AtomicReference<Throwable>();
    var restoreBody = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var path = exchange.getRequestURI().getPath();
            String operation =
                path.endsWith("/lifecycle-status")
                    ? "account.lifecycle-status"
                    : path.endsWith("/suspension") ? "account.suspend" : "account.restore";
            InactivityCommandTestSupport.verify(exchange, body, "owned", 7L, operation);
            assertThat(path)
                .isEqualTo(
                    "/realms/test/oriso-commands/v1/accounts/owned/"
                        + (operation.equals("account.lifecycle-status")
                            ? "lifecycle-status"
                            : operation.equals("account.suspend")
                                ? "suspension"
                                : "access-restoration"));
            calls.incrementAndGet();
            if (operation.equals("account.restore")) restoreBody.set(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (operation.equals("account.lifecycle-status")) {
              var bytes =
                  "{\"enabled\":false,\"sessionCount\":0,\"roles\":[\"ASKER\"]}"
                      .getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
            } else exchange.sendResponseHeaders(204, -1);
          } catch (Throwable failure) {
            httpFailure.set(failure);
            exchange.sendResponseHeaders(500, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var lifecycle =
          InactivityCommandTestSupport.lifecycle(
              jdbc, "http://127.0.0.1:" + server.getAddress().getPort());
      assertThat(lifecycle.status("owned").roles())
          .containsExactly(AccountInactivityEffects.Role.ASKER);
      assertThatThrownBy(() -> lifecycle.suspend("owned"))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> lifecycle.status("foreign"))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> lifecycle.authorizeDeletion("owned"))
          .isInstanceOf(AccessDeniedException.class);
      assertThat(calls).hasValue(1);
      jdbc.update("UPDATE account_inactivity SET status='SUSPENDING'");
      lifecycle.suspend("owned");
      jdbc.update("INSERT INTO account_inactivity_access_state VALUES('owned',FALSE,FALSE,TRUE)");
      assertThatThrownBy(() -> lifecycle.authorizeDeletion("owned"))
          .isInstanceOf(AccessDeniedException.class);
      jdbc.update("UPDATE account_inactivity SET status='DELETING'");
      var capability = lifecycle.authorizeDeletion("owned");
      capability.requireLifecycleDeletion("owned");
      assertThatThrownBy(() -> capability.requireLifecycleDeletion("foreign"))
          .isInstanceOf(AccessDeniedException.class);
      jdbc.update("UPDATE account_inactivity_access_state SET deletion_authorized=FALSE");
      assertThatThrownBy(() -> lifecycle.authorizeDeletion("owned"))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> lifecycle.restoreOriginalAccess("owned"))
          .isInstanceOf(AccessDeniedException.class);
      jdbc.update("UPDATE account_inactivity SET status='REACTIVATING'");
      lifecycle.restoreOriginalAccess("owned");
      assertThat(restoreBody.get()).isEqualTo("{\"enabled\":false}");
      jdbc.update("UPDATE account_inactivity_access_state SET restored=TRUE");
      assertThatThrownBy(() -> lifecycle.restoreOriginalAccess("owned"))
          .isInstanceOf(AccessDeniedException.class);
      assertThat(calls).hasValue(3);
    } finally {
      server.stop(0);
      assertThat(httpFailure.get()).isNull();
    }
  }
}
