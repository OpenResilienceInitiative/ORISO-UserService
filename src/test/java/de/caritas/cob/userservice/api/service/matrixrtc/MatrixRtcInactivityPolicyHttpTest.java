package de.caritas.cob.userservice.api.service.matrixrtc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.web.controller.MatrixRtcCallPolicyController;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

@DataJpaTest
@org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase(
    replace =
        org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("testing")
class MatrixRtcInactivityPolicyHttpTest {
  @Autowired UserRepository users;
  @Autowired SessionRepository sessions;
  @Autowired ChatRepository chats;
  @Autowired SessionSupervisorRepository supervisors;
  @Autowired TeamDiscussionRepository discussions;
  @Autowired javax.sql.DataSource dataSource;
  @Autowired jakarta.persistence.EntityManager entityManager;

  @Test
  void newGrantsRequireAnActivePersistedIdentityAndNeverRenewItsClock() throws Exception {
    var user = new User("rtc-policy-person", null, "rtc-policy", "rtc@example.invalid", false);
    user.setMatrixUserId("@rtc:matrix.test");
    user.setTenantId(7L);
    user = users.save(user);
    var session = new Session(user, 0, "12345", null, Session.SessionStatus.NEW, false);
    session.setLanguageCode(com.neovisionaries.i18n.LanguageCode.de);
    session.setTenantId(7L);
    session.setIsConsultantDirectlySet(false);
    session.setMatrixRoomId("!rtc:matrix.test");
    session.setConversationType(ConversationType.AGENCY_COUNSELLING);
    sessions.save(session);
    entityManager.flush();
    var jdbc = new JdbcTemplate(dataSource);
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS account_inactivity(identity_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT,assigned_months INT NOT NULL,revision BIGINT NOT NULL,last_activity TIMESTAMP(6) NOT NULL,due_at TIMESTAMP(6) NOT NULL,status VARCHAR(20) NOT NULL,last_error VARCHAR(1000),attempts INT DEFAULT 0 NOT NULL)");
    jdbc.update(
        "INSERT INTO account_inactivity VALUES (?,7,24,0,TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2028-01-01 00:00:00','SUSPENDED',NULL,0)",
        user.getUserId());
    var remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    remote.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          String body =
              path.endsWith("/login")
                  ? "{\"access_token\":\"test-only\"}"
                  : path.contains("/members")
                      ? "{\"members\":[\"@rtc:matrix.test\"]}"
                      : "{\"id\":7,\"settings\":{\"featureCallsEnabled\":true,\"featureAudioCallsEnabled\":true,\"featureVideoCallsEnabled\":true}}";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    remote.start();
    try {
      String url = "http://127.0.0.1:" + remote.getAddress().getPort();
      var transport = new RestTemplate();
      var config = new MatrixConfig();
      config.setApiUrl(url);
      config.setAdminUsername("admin");
      config.setAdminPassword("test-only");
      var matrix = new MatrixSynapseService(config, transport, transport, null, null);
      var factory = new TenantServiceApiControllerFactory();
      ReflectionTestUtils.setField(factory, "tenantServiceApiUrl", url);
      ReflectionTestUtils.setField(factory, "restTemplate", transport);
      var hasher = new MatrixRtcCorrelationIdHasher();
      ReflectionTestUtils.setField(hasher, "secret", "test-only-secret");
      hasher.init();
      var service =
          new MatrixRtcCallPolicyService(
              new MatrixRtcPolicyContextResolver(sessions, chats, supervisors, discussions),
              new TenantService(factory, new ConcurrentMapCacheManager()),
              matrix,
              hasher,
              jdbc);
      var mvc =
          MockMvcBuilders.standaloneSetup(
                  new MatrixRtcCallPolicyController(
                      service, new MatrixRtcPolicyTokenVerifier("test-only")))
              .build();
      for (String state :
          java.util.List.of("SUSPENDED", "DELETING", "SUSPENDING", "REACTIVATING", "DELETED")) {
        jdbc.update(
            "UPDATE account_inactivity SET status=? WHERE identity_id=?", state, user.getUserId());
        mvc.perform(
                post("/internal/matrixrtc/call-policy")
                    .header("x-matrixrtc-policy-token", "test-only")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"sourceRoomId\":\"!rtc:matrix.test\",\"matrixUserId\":\"@rtc:matrix.test\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.audioAllowed").value(false))
            .andExpect(jsonPath("$.videoAllowed").value(false));
      }
      jdbc.update(
          "UPDATE account_inactivity SET status='ACTIVE' WHERE identity_id=?", user.getUserId());
      mvc.perform(
              post("/internal/matrixrtc/call-policy")
                  .header("x-matrixrtc-policy-token", "test-only")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"sourceRoomId\":\"!rtc:matrix.test\",\"matrixUserId\":\"@rtc:matrix.test\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.audioAllowed").value(true));
      org.assertj.core.api.Assertions.assertThat(
              jdbc.queryForObject(
                      "SELECT last_activity FROM account_inactivity WHERE identity_id=?",
                      java.sql.Timestamp.class,
                      user.getUserId())
                  .toLocalDateTime())
          .isEqualTo(java.time.LocalDateTime.parse("2026-01-01T00:00:00"));
      jdbc.update("DELETE FROM account_inactivity WHERE identity_id=?", user.getUserId());
      mvc.perform(
              post("/internal/matrixrtc/call-policy")
                  .header("x-matrixrtc-policy-token", "test-only")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"sourceRoomId\":\"!rtc:matrix.test\",\"matrixUserId\":\"@rtc:matrix.test\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.audioAllowed").value(false));
      String consultantId =
          jdbc.queryForList("SELECT consultant_id FROM consultant", String.class).getFirst();
      jdbc.update(
          "UPDATE consultant SET matrix_user_id=? WHERE consultant_id=?",
          "@rtc:matrix.test",
          consultantId);
      jdbc.update(
          "INSERT INTO account_inactivity VALUES (?,7,24,0,TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2028-01-01 00:00:00','ACTIVE',NULL,0)",
          consultantId);
      // Two distinct realm subjects with one Matrix binding must never pick the active one.
      expectPolicy(mvc, false);
      jdbc.update("UPDATE user SET matrix_user_id=NULL WHERE user_id=?", user.getUserId());
      expectPolicy(mvc, true);
      jdbc.update(
          "UPDATE account_inactivity SET status='SUSPENDED' WHERE identity_id=?", consultantId);
      expectPolicy(mvc, false);
      jdbc.update("UPDATE consultant SET matrix_user_id=NULL WHERE consultant_id=?", consultantId);
      expectPolicy(mvc, false);
    } finally {
      remote.stop(0);
    }
  }

  private void expectPolicy(org.springframework.test.web.servlet.MockMvc mvc, boolean allowed)
      throws Exception {
    mvc.perform(
            post("/internal/matrixrtc/call-policy")
                .header("x-matrixrtc-policy-token", "test-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceRoomId\":\"!rtc:matrix.test\",\"matrixUserId\":\"@rtc:matrix.test\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.audioAllowed").value(allowed))
        .andExpect(jsonPath("$.videoAllowed").value(allowed));
  }
}
