package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.config.apiclient.*;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.httpheader.*;
import de.caritas.cob.userservice.api.workflow.accountinactivity.*;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.*;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

@DataJpaTest
@org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase(
    replace =
        org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("testing")
class AccountInactivityDeletionCompletionTest {
  @Autowired UserRepository users;
  @Autowired DraftMessageRepository drafts;
  @Autowired EventNotificationRepository notifications;
  @Autowired jakarta.persistence.EntityManager entityManager;

  @Autowired javax.sql.DataSource dataSource;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  private final String id = "completion-retry-asker";

  @org.junit.jupiter.api.AfterEach
  void removeOwnedCommittedRows() {
    var jdbc = new JdbcTemplate(dataSource);
    for (var table :
        java.util.List.of(
            "account_inactivity_journal",
            "account_inactivity_matrix_state",
            "account_inactivity_access_state",
            "account_inactivity")) jdbc.update("DELETE FROM " + table + " WHERE identity_id=?", id);
    new org.springframework.transaction.support.TransactionTemplate(transactions)
        .executeWithoutResult(
            ignored -> {
              drafts.findAll().stream()
                  .filter(d -> id.equals(d.getUserId()))
                  .forEach(drafts::delete);
              notifications.findAll().stream()
                  .filter(n -> id.equals(n.getRecipientUserId()))
                  .forEach(notifications::delete);
            });
  }

  @Test
  @org.springframework.transaction.annotation.Transactional(
      propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  void deletionKeepsRecoveryMetadataUntilEveryExternalCleanupIsConfirmed() throws Exception {
    var jdbc = new JdbcTemplate(dataSource);
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS account_inactivity(identity_id VARCHAR(36) PRIMARY"
            + " KEY,tenant_id BIGINT,assigned_months INT NOT NULL,revision BIGINT NOT"
            + " NULL,last_activity TIMESTAMP(6) NOT NULL,due_at TIMESTAMP(6) NOT NULL,status"
            + " VARCHAR(20) NOT NULL,last_error VARCHAR(1000),attempts INT DEFAULT 0 NOT NULL)");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS account_inactivity_journal(journal_id BIGINT AUTO_INCREMENT"
            + " PRIMARY KEY,identity_id VARCHAR(36),action VARCHAR(30),target VARCHAR(30),outcome"
            + " VARCHAR(40),created_at TIMESTAMP(6))");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS account_inactivity_access_state(identity_id VARCHAR(36) PRIMARY"
            + " KEY,keycloak_enabled BOOLEAN NOT NULL,restored BOOLEAN NOT NULL,deletion_authorized"
            + " BOOLEAN NOT NULL)");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS account_inactivity_matrix_state(identity_id"
            + " VARCHAR(36),matrix_user_id VARCHAR(255),original_locked BOOLEAN NOT NULL,PRIMARY"
            + " KEY(identity_id,matrix_user_id))");
    assertThat(users.findById(id)).isEmpty();
    var now = LocalDateTime.now();
    drafts.save(
        DraftMessage.builder()
            .userId(id)
            .scopeKey("remaining")
            .text("private")
            .createDate(now)
            .updateDate(now)
            .build());
    notifications.save(
        EventNotification.builder()
            .recipientUserId(id)
            .eventType("test")
            .category("test")
            .title("private")
            .createDate(now)
            .build());

    var failIdentity = new AtomicBoolean(true);
    var failMedia = new AtomicBoolean(true);
    var mediaCalls = new AtomicInteger();
    var mediaBodies = new java.util.ArrayList<String>();
    var identityCalls = new AtomicInteger();
    var remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    remote.createContext(
        "/",
        exchange -> {
          int status;
          if (exchange.getRequestURI().getPath().startsWith("/admin/")) {
            status = 404;
          } else if (exchange.getRequestURI().getPath().equals("/internal/lifecycle/forget")) {
            mediaCalls.incrementAndGet();
            mediaBodies.add(
                new String(
                    exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
            status = failMedia.get() ? 503 : 204;
          } else if (exchange.getRequestURI().getPath().startsWith("/identity/")) {
            identityCalls.incrementAndGet();
            status = failIdentity.get() ? 503 : 404;
          } else {
            status = 204;
          }
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    remote.start();
    try (var context = new StaticApplicationContext()) {
      String url = "http://127.0.0.1:" + remote.getAddress().getPort();
      var transport = new RestTemplate();
      var remover =
          new IdentityAccountRemover() {
            public void deleteUser(String subject) {
              transport.delete(url + "/identity/" + subject);
            }

            public void rollbackUser(String subject) {
              throw new UnsupportedOperationException();
            }
          };
      context
          .getBeanFactory()
          .registerSingleton("drafts", new DeleteAskerDraftMessagesAction(drafts));
      context
          .getBeanFactory()
          .registerSingleton(
              "notifications", new DeleteAskerEventNotificationsAction(notifications));
      context
          .getBeanFactory()
          .registerSingleton("identity", new DeleteKeycloakAskerAction(remover));
      var service = new InactiveAskerDeletionService(users, new ActionsRegistry(context));
      context.getBeanFactory().registerSingleton("deletion", service);
      var kcConfig = new de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig();
      kcConfig.setRealm("test");
      var matrixConfig = new de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig();
      matrixConfig.setApiUrl(url);
      try (var kc =
          org.keycloak.admin.client.KeycloakBuilder.builder()
              .serverUrl(url)
              .realm("test")
              .authorization("test-token")
              .build()) {
        var effects =
            new DefaultAccountInactivityEffects(
                jdbc,
                transactions,
                new de.caritas.cob.userservice.api.adapters.keycloak.KeycloakClient(
                    transport, kc, kcConfig),
                new de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService(
                    matrixConfig,
                    transport,
                    transport,
                    null,
                    null,
                    org.mockito.Mockito.mock(
                        de.caritas.cob.userservice.api.adapters.matrix.MatrixIdentifierRedactor
                            .class)),
                new AccountInactivityMediaClient(
                    transport, true, url, "test-only-media-lifecycle-secret-32"),
                context.getBeanProvider(InactiveAskerDeletionService.class));
        var lifecycle =
            new AccountInactivityService(
                jdbc,
                transactions,
                Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC),
                effects);
        lifecycle.assignAtCreation(id, 7L, 24, 0, Instant.parse("2023-01-01T00:00:00Z"));
        // A prior attempt already removed the identity; durable authorization permits cleanup
        // retries.
        jdbc.update("UPDATE account_inactivity SET status='DELETING' WHERE identity_id=?", id);
        jdbc.update("INSERT INTO account_inactivity_access_state VALUES(?,TRUE,FALSE,TRUE)", id);
        jdbc.update(
            "INSERT INTO account_inactivity_matrix_state VALUES(?,?,FALSE)",
            id,
            "@completion:matrix.test");
        lifecycle.scan(false);
        assertPending(lifecycle, jdbc, "KEYCLOAK:UNCONFIRMED");
        assertThat(mediaCalls).hasValue(0);
        assertThat(identityCalls).hasValue(1);
        failIdentity.set(false);
        lifecycle.scan(false);
        assertPending(lifecycle, jdbc, "MEDIA:UNCONFIRMED");
        assertThat(mediaCalls).hasValue(1);
        failMedia.set(false);
        lifecycle.scan(false);
        assertThat(lifecycle.snapshot(id).orElseThrow().status())
            .isEqualTo(AccountInactivityService.Status.DELETED);
        assertThat(lifecycle.snapshot(id).orElseThrow().attempts()).isEqualTo(3);
        assertThat(lifecycle.journal(id)).hasSize(3);
        assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM account_inactivity_access_state WHERE identity_id=?",
                    Integer.class,
                    id))
            .isZero();
        assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM account_inactivity_matrix_state WHERE identity_id=?",
                    Integer.class,
                    id))
            .isZero();
        assertThat(mediaBodies)
            .hasSize(2)
            .allSatisfy(body -> assertThat(body).contains("@completion:matrix.test"));
        assertThat(drafts.findAll().stream().anyMatch(d -> id.equals(d.getUserId()))).isFalse();
        assertThat(
                notifications.findAll().stream().anyMatch(n -> id.equals(n.getRecipientUserId())))
            .isFalse();
        lifecycle.scan(false);
        assertThat(mediaCalls).hasValue(2);
        assertThat(identityCalls).hasValue(3);
        assertThat(users.findById(id)).isEmpty();
      }
    } finally {
      remote.stop(0);
    }
  }

  private void assertPending(AccountInactivityService lifecycle, JdbcTemplate jdbc, String error) {
    assertThat(lifecycle.snapshot(id).orElseThrow().status())
        .isEqualTo(AccountInactivityService.Status.DELETING);
    assertThat(lifecycle.snapshot(id).orElseThrow().lastError()).isEqualTo(error);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_inactivity_access_state WHERE identity_id=?",
                Integer.class,
                id))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_inactivity_matrix_state WHERE identity_id=?",
                Integer.class,
                id))
        .isEqualTo(1);
  }
}
