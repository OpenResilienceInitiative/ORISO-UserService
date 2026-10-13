package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.adapters.web.controller.AccountInactivityMediaController;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffectException;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityMediaClient;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

class AccountInactivityMediaTest {
  private static final String SECRET = "test-only-media-lifecycle-secret-32";

  @Test
  void mediaRevocationRequiresConfirmedExternalResultAndRetries() throws Exception {
    var calls = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/lifecycle/revoke",
        exchange -> {
          assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
              .isEqualTo("Bearer " + SECRET);
          assertThat(new String(exchange.getRequestBody().readAllBytes()))
              .contains("@person:example");
          exchange.sendResponseHeaders(calls.incrementAndGet() == 1 ? 503 : 204, -1);
          exchange.close();
        });
    server.start();
    try {
      var client =
          new AccountInactivityMediaClient(
              new RestTemplate(),
              true,
              "http://127.0.0.1:" + server.getAddress().getPort(),
              SECRET);
      assertThatThrownBy(() -> client.revoke(List.of("@person:example")))
          .isInstanceOf(AccountInactivityEffectException.class)
          .hasMessage("MEDIA:UNCONFIRMED");
      client.revoke(List.of("@person:example"));
      assertThat(calls.get()).isEqualTo(2);
      var disabled = new AccountInactivityMediaClient(new RestTemplate(), false, "", "");
      assertThatThrownBy(() -> disabled.revoke(List.of("@person:example")))
          .isInstanceOf(AccountInactivityEffectException.class);
      disabled.revoke(List.of());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void privateAdmissionRequiresSecretAndExactUniqueActiveBindingWithoutRecordingActivity()
      throws Exception {
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:h2:mem:media-"
                    + java.util.UUID.randomUUID()
                    + ";DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER",
                "sa",
                ""));
    jdbc.execute("CREATE TABLE user(user_id VARCHAR(36),matrix_user_id VARCHAR(255))");
    jdbc.execute("CREATE TABLE consultant(consultant_id VARCHAR(36),matrix_user_id VARCHAR(255))");
    jdbc.execute("CREATE TABLE account_inactivity(identity_id VARCHAR(36),status VARCHAR(20))");
    jdbc.update("INSERT INTO user VALUES('person','@person:example')");
    jdbc.update("INSERT INTO account_inactivity VALUES('person','ACTIVE')");
    var mvc =
        MockMvcBuilders.standaloneSetup(new AccountInactivityMediaController(jdbc, true, SECRET))
            .build();
    var path = "/internal/matrixrtc/media-access";
    var body = "{\"matrixUserId\":\"@person:example\"}";
    mvc.perform(post(path).contentType("application/json").content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(path)
                .header("x-matrixrtc-lifecycle-token", SECRET)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isNoContent());
    for (String state : List.of("SUSPENDING", "SUSPENDED", "DELETING", "REACTIVATING")) {
      jdbc.update("UPDATE account_inactivity SET status=?", state);
      mvc.perform(
              post(path)
                  .header("x-matrixrtc-lifecycle-token", SECRET)
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isForbidden());
    }
    jdbc.update("UPDATE account_inactivity SET status='ACTIVE'");
    jdbc.update("INSERT INTO consultant VALUES('other','@person:example')");
    mvc.perform(
            post(path)
                .header("x-matrixrtc-lifecycle-token", SECRET)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    jdbc.update("DELETE FROM consultant");
    jdbc.update("DELETE FROM account_inactivity");
    mvc.perform(
            post(path)
                .header("x-matrixrtc-lifecycle-token", SECRET)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    jdbc.update("DELETE FROM user");
    mvc.perform(
            post(path)
                .header("x-matrixrtc-lifecycle-token", SECRET)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isGone());
  }
}
