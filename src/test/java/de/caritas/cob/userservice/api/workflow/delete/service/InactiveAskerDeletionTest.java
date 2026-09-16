package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteMatrixAskerAction;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

@DataJpaTest
@org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase(
    replace =
        org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("testing")
class InactiveAskerDeletionTest {
  @Autowired UserRepository users;
  @Autowired SessionRepository sessions;

  @Autowired javax.sql.DataSource dataSource;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

  @Test
  @org.springframework.transaction.annotation.Transactional(
      propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  void deletionLocksExistingAccessBeforeDestructiveMatrixCallsAndRetainsFailedWork()
      throws Exception {
    var user =
        new User("inactivity-lock-first", null, "lock-first", "lock-first@example.invalid", false);
    user.setMatrixUserId("@lock-first:matrix.test");
    users.save(user);
    var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS account_inactivity_access_state(identity_id VARCHAR(36) PRIMARY KEY,keycloak_enabled BOOLEAN NOT NULL,restored BOOLEAN NOT NULL,deletion_authorized BOOLEAN NOT NULL)");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS account_inactivity_matrix_state(identity_id VARCHAR(36),matrix_user_id VARCHAR(255),original_locked BOOLEAN NOT NULL,PRIMARY KEY(identity_id,matrix_user_id))");
    try (var remote = new InactivityDeletionRemote();
        var context = new StaticApplicationContext()) {
      var config = new MatrixConfig();
      config.setApiUrl(remote.url());
      config.setAdminUsername("admin");
      config.setAdminPassword("test-only");
      var transport = new RestTemplate();
      var matrix = new MatrixSynapseService(config, transport, transport, null, null);
      context
          .getBeanFactory()
          .registerSingleton("matrixDeletion", new DeleteMatrixAskerAction(matrix, sessions));
      var deletion = new InactiveAskerDeletionService(users, new ActionsRegistry(context));
      context.getBeanFactory().registerSingleton("deletion", deletion);
      var keycloakConfig =
          new de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig();
      keycloakConfig.setRealm("test");
      try (var kc =
          org.keycloak.admin.client.KeycloakBuilder.builder()
              .serverUrl(remote.url())
              .realm("test")
              .authorization("test-token")
              .build()) {
        var effects =
            new de.caritas.cob.userservice.api.workflow.accountinactivity
                .DefaultAccountInactivityEffects(
                jdbc,
                transactions,
                new de.caritas.cob.userservice.api.adapters.keycloak.KeycloakClient(
                    transport, kc, keycloakConfig),
                matrix,
                context.getBeanProvider(InactiveAskerDeletionService.class));
        for (int attempt = 0; attempt < 2; attempt++) {
          org.assertj.core.api.Assertions.assertThatThrownBy(() -> effects.delete(user.getUserId()))
              .isInstanceOfSatisfying(
                  de.caritas.cob.userservice.api.workflow.accountinactivity
                      .AccountInactivityEffectException.class,
                  error ->
                      assertThat(error.target())
                          .isEqualTo(
                              de.caritas.cob.userservice.api.workflow.accountinactivity
                                  .AccountInactivityEffectException.Target.MATRIX));
          assertThat(users.findById(user.getUserId())).isPresent();
        }
        assertThat(remote.deactivations.get()).isEqualTo(2);
        assertThat(remote.unsafeDestructiveCall.get()).isFalse();
        assertThat(remote.enabled.get()).isFalse();
        assertThat(remote.locked.get()).isTrue();
      }
    }
  }

  @Test
  void matrixFailureRetainsTheAccountAndCanBeRetriedWithoutStartingANewGracePeriod() {
    var user = new User("inactivity-retry", null, "retry", "retry@example.invalid", false);
    user.setMatrixUserId("@retry:matrix.test");
    users.save(user);
    var transport = new RestTemplate();
    var server = MockRestServiceServer.bindTo(transport).build();
    var config = new MatrixConfig();
    config.setApiUrl("https://matrix.test");
    config.setAdminUsername("admin");
    config.setAdminPassword("test-only");
    server
        .expect(requestTo("https://matrix.test/_matrix/client/r0/login"))
        .andRespond(withSuccess("{\"access_token\":\"test-admin\"}", MediaType.APPLICATION_JSON));
    for (int attempt = 0; attempt < 2; attempt++) {
      server
          .expect(requestTo(org.hamcrest.Matchers.containsString("/_synapse/admin/v1/deactivate/")))
          .andRespond(withServerError());
    }
    var matrix = new MatrixSynapseService(config, transport, transport, null, null);
    try (var context = new StaticApplicationContext()) {
      context
          .getBeanFactory()
          .registerSingleton("matrixDeletion", new DeleteMatrixAskerAction(matrix, sessions));
      var deletion = new InactiveAskerDeletionService(users, new ActionsRegistry(context));
      for (int attempt = 0; attempt < 2; attempt++) {
        var failures = deletion.delete(user.getUserId());
        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst().getDeletionTargetType())
            .isEqualTo(DeletionTargetType.MATRIX);
      }
      server.verify();
    }
  }
}
