package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** SQL/outbox proof with an explicit remote port double; not native Keycloak acceptance. */
class OrdinaryCreationWorkflowSqlTest {
  private JdbcDataSource source;
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager manager;
  private JdbcAccountLifecycleJournal journal;
  private IdentityProvisioningCommands remote;
  private AccountLifecycleCodec codec;
  private OrdinaryAccountCreation flow;
  private final CreationScope scope =
      new CreationScope(
          "7",
          "CONSULTANT",
          "/human-roles/consultant",
          new Origin("INVITATION", "invite:41", "invite:41", null, null, null, List.of("12")));
  private final AccountCreation account =
      new AccountCreation(
          "synthetic",
          "synthetic@example.test",
          "Synthetic",
          "User",
          "de",
          "synthetic-password",
          false);

  @BeforeEach
  void database() throws Exception {
    source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    jdbc = new JdbcTemplate(source);
    manager = new DataSourceTransactionManager(source);
    for (String sql :
        Files.readString(
                Path.of(
                    "src/main/resources/db/changelog/changeset/20261021_identity_lifecycle_367/migrate.sql"))
            .split(";")) if (!sql.isBlank()) jdbc.execute(sql);
    jdbc.execute("CREATE TABLE local_created(id VARCHAR(255) PRIMARY KEY)");
    codec = new AccountLifecycleCodec(new byte[32]);
    for (String retrySql :
        Files.readString(
                Path.of(
                    "src/main/resources/db/changelog/changeset/20261021_identity_lifecycle_367/retry-fairness.sql"))
            .split(";")) if (!retrySql.isBlank()) jdbc.execute(retrySql);
    journal = new JdbcAccountLifecycleJournal(jdbc, manager, codec, Clock.systemUTC(), 300);
    remote = mock(IdentityProvisioningCommands.class);
    flow = restarted();
    when(remote.create(any(), any()))
        .thenAnswer(
            call -> {
              UUID id = call.getArgument(0);
              CreateRequest request = call.getArgument(1);
              return new AttemptResult(
                  id, "owned-account", "OPEN", request.execution(), "proof", null, null);
            });
    when(remote.ownedRead(any(), any()))
        .thenReturn(
            new AccountProjection(
                "owned-account",
                "synthetic",
                account.email(),
                "Synthetic",
                "User",
                "7",
                "de",
                false,
                false,
                List.of(),
                false));
  }

  private OrdinaryAccountCreation restarted() {
    var effects =
        new JdbcOwnedLifecycleEffects(
            jdbc, manager, mock(JdbcOwnedLifecycleEffects.CleanupOperations.class), codec);
    var dispatcher = new AccountLifecycleDispatcher(remote, journal, effects, codec);
    var coordinator = new AccountLifecycleCoordinator(remote, journal, dispatcher, codec);
    return new OrdinaryAccountCreation(
        coordinator,
        journal,
        remote,
        effects,
        codec,
        mock(MatrixSynapseService.class),
        mock(MatrixConfig.class),
        mock(AppointmentService.class),
        mock(AuthenticatedUser.class),
        "https://issuer.example/realms/oriso",
        Set.of("human-admin"),
        Map.of("/human-roles/consultant", Set.of("consultant")),
        mock(OrdinaryCreationTenantResolver.class));
  }

  @Test
  void receiverCommitSeesDomainSuccessAlreadyCommittedOnAnotherPhysicalConnection()
      throws Exception {
    when(remote.commit(any(), any()))
        .thenAnswer(
            call -> {
              try (var connection = source.getConnection();
                  var query = connection.prepareStatement("SELECT COUNT(*) FROM local_created");
                  var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
              }
              CommitRequest request = call.getArgument(1);
              UUID id = call.getArgument(0);
              return new AttemptResult(
                  id, "owned-account", "COMMITTED", request.execution(), "proof", null, "terminal");
            });
    assertEquals(
        "done",
        flow.create(
            scope,
            account,
            pending -> {
              jdbc.update("INSERT INTO local_created(id) VALUES (?)", pending.accountId());
              return "done";
            }));
    var saved = journal.findWorkflow("INVITATION", "invite:41", 7L, "owned-account").orElseThrow();
    journal.requireCommitted(saved.id());
    verify(remote).commit(any(), any());
  }

  @Test
  void outerRollbackLeavesRecoveryIntentAndCannotActivateRemoteAccount() {
    assertThrows(
        IllegalStateException.class,
        () ->
            new TransactionTemplate(manager)
                .execute(
                    status -> {
                      flow.create(
                          scope,
                          account,
                          pending -> {
                            jdbc.update(
                                "INSERT INTO local_created(id) VALUES (?)", pending.accountId());
                            return null;
                          });
                      throw new IllegalStateException("later held-invite failure");
                    }));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM local_created", Integer.class));
    var saved = journal.findWorkflow("INVITATION", "invite:41", 7L, "owned-account").orElseThrow();
    assertEquals("RECOVERY_REQUESTED", saved.status());
    verify(remote, never()).commit(any(), any());
  }

  @Test
  void unknownActivationReplySurvivesRestartAndSameInviteRetriesSameCommittedIntent() {
    AtomicReference<CommitRequest> first = new AtomicReference<>();
    when(remote.commit(any(), any()))
        .thenAnswer(
            call -> {
              CommitRequest request = call.getArgument(1);
              UUID id = call.getArgument(0);
              if (first.compareAndSet(null, request))
                throw new KeycloakAccountLifecycleCommands.RemoteFailure(0);
              assertEquals(first.get(), request);
              return new AttemptResult(
                  id, "owned-account", "COMMITTED", request.execution(), "proof", null, "terminal");
            });
    assertThrows(
        ResponseStatusException.class,
        () ->
            flow.create(
                scope,
                account,
                pending -> {
                  jdbc.update("INSERT INTO local_created(id) VALUES (?)", pending.accountId());
                  return null;
                }));
    var saved = journal.findWorkflow("INVITATION", "invite:41", 7L, "owned-account").orElseThrow();
    assertEquals("COMMIT_REQUESTED", saved.status());
    var invite = new AccountInvite();
    invite.setId(41L);
    invite.setTenantId(7L);
    invite.setProvisionedUserId("owned-account");
    assertTrue(restarted().finishInvitationIfPending(invite));
    assertFalse(restarted().finishInvitationIfPending(invite));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM local_created", Integer.class));
    journal.requireCommitted(saved.id());
    verify(remote).create(any(), any());
    verify(remote, times(2)).commit(any(), any());
  }
}
