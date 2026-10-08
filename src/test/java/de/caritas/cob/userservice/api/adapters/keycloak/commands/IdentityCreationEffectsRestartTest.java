package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Exact acknowledged remote effects survive rollback and a reopened file-backed database. */
class IdentityCreationEffectsRestartTest {
  @TempDir Path directory;
  private static final Instant START = Instant.parse("2026-10-08T10:00:00Z");

  @Test
  void crashAfterPrivateRoomAcknowledgmentCleansRoomThenIdentityBeforeNativeTerminalIntent()
      throws Exception {
    var source = source();
    var id = UUID.randomUUID();
    captureByAbruptDeath(source, id);
    try (var context = open(source, false, 61)) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      recover(journal, id);
      var matrix = context.getBean(MatrixSynapseService.class);
      when(matrix.purgeRoom("!owned:matrix.example.com")).thenReturn(true);
      when(matrix.deactivateUser("@owned:matrix.example.com")).thenReturn(true);
      context.getBean(IdentityCreationEffects.class).clean(id);
      var order = inOrder(matrix);
      order.verify(matrix).purgeRoom("!owned:matrix.example.com");
      order.verify(matrix).deactivateUser("@owned:matrix.example.com");
      verifyNoMoreInteractions(matrix); // existing shared group room was never captured
      assertThat(journal.attempt(id).getStatus()).isEqualTo("LOCAL_CLEANUP_REQUESTED");
    }
    try (var context = open(source, false, 62)) {
      var matrix = context.getBean(MatrixSynapseService.class);
      context.getBean(IdentityCreationEffects.class).clean(id);
      verifyNoInteractions(matrix); // durable acknowledged cleanup is idempotent after restart
    }
  }

  @Test
  void priorDeactivatedIdentityRestorationSurvivesPasswordFailureAndRetry() throws Exception {
    var source = source();
    var id = UUID.randomUUID();
    capture(source, id, "REACTIVATED_FROM_DEACTIVATED", false);
    try (var context = open(source, false, 61)) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      recover(journal, id);
      var matrix = context.getBean(MatrixSynapseService.class);
      when(matrix.restoreDeactivatedUser("@owned:matrix.example.com")).thenReturn(false);
      assertThatThrownBy(() -> context.getBean(IdentityCreationEffects.class).clean(id))
          .isInstanceOf(IllegalStateException.class);
      assertThat(journal.attempt(id).getStatus()).isEqualTo("LOCAL_CLEANUP_REQUESTED");
    }
    try (var context = open(source, false, 62)) {
      var matrix = context.getBean(MatrixSynapseService.class);
      when(matrix.restoreDeactivatedUser("@owned:matrix.example.com")).thenReturn(true);
      context.getBean(IdentityCreationEffects.class).clean(id);
      verify(matrix).restoreDeactivatedUser("@owned:matrix.example.com");
      verifyNoMoreInteractions(matrix);
    }
  }

  @Test
  void unknownOutcomeRemainsNonterminalAndNeverGuessesOwnership() throws Exception {
    var source = source();
    var id = UUID.randomUUID();
    capture(source, id, "UNKNOWN", false);
    try (var context = open(source, false, 61)) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      recover(journal, id);
      assertThatThrownBy(() -> context.getBean(IdentityCreationEffects.class).clean(id))
          .isInstanceOf(IllegalStateException.class);
      assertThat(journal.attempt(id).getStatus()).isEqualTo("LOCAL_CLEANUP_REQUESTED");
      verifyNoInteractions(context.getBean(MatrixSynapseService.class));
    }
  }

  @Test
  void foreignAcknowledgmentCannotCreateCleanupAuthority() throws Exception {
    var source = source();
    var id = UUID.randomUUID();
    capture(source, id, "FOREIGN", false);
    try (var context = open(source, false, 61)) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      recover(journal, id);
      assertThatThrownBy(() -> context.getBean(IdentityCreationEffects.class).clean(id))
          .isInstanceOf(IllegalStateException.class);
      verifyNoInteractions(context.getBean(MatrixSynapseService.class));
    }
  }

  @Test
  void committedCreationAndStaleExecutionCannotBeAdoptedByRecovery() throws Exception {
    var source = source();
    var id = UUID.randomUUID();
    try (var context = open(source, true, 0)) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      var receipt = create(journal, id);
      tx(context)
          .executeWithoutResult(
              s -> {
                var observer =
                    context.getBean(IdentityCreationEffects.class).capture(receipt).user();
                observer.started("@owned:matrix.example.com");
                observer.created("@owned:matrix.example.com");
                journal.prepareCommitInSaga(receipt, IdentityCreationJournalRestartTest.origin());
              });
      journal.finish(receipt, "COMMITTED");
      assertThatThrownBy(() -> context.getBean(IdentityCreationEffects.class).clean(id))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      verifyNoInteractions(context.getBean(MatrixSynapseService.class));
    }
  }

  @Test
  void staleCapturedObserverCannotStartAnEffectAfterNativeRecoveryFencesItsExecution()
      throws Exception {
    var source = source();
    var id = UUID.randomUUID();
    de.caritas.cob.userservice.api.port.out.OwnedMatrixEffect observer;
    try (var context = open(source, true, 0)) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      var receipt = create(journal, id);
      observer =
          tx(context)
              .execute(s -> context.getBean(IdentityCreationEffects.class).capture(receipt).user());
      try (var recovery = open(source, false, 61)) {
        recover(recovery.getBean(IdentityCreationJournalWriter.class), id);
        var stale = observer;
        assertThatThrownBy(
                () ->
                    tx(context)
                        .executeWithoutResult(s -> stale.started("@owned:matrix.example.com")))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        recovery.getBean(IdentityCreationEffects.class).clean(id);
        verifyNoInteractions(recovery.getBean(MatrixSynapseService.class));
      }
    }
  }

  @Test
  void consultantCreationCannotCaptureARegistrationPrivateRoom() throws Exception {
    var source = source();
    var id = UUID.randomUUID();
    try (var context = open(source, true, 0)) {
      var receipt = create(context.getBean(IdentityCreationJournalWriter.class), id);
      tx(context)
          .executeWithoutResult(
              s -> {
                var scope = context.getBean(IdentityCreationEffects.class).capture(receipt);
                assertThatThrownBy(scope::privateRoom)
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                assertThatThrownBy(() -> scope.requireOwner("foreign-account", 42L))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                assertThatThrownBy(() -> scope.requireOwner(receipt.accountId(), 99L))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
              });
    }
  }

  @Test
  void acknowledgedAppointmentMustBeCleanedAfterLocalRollbackAndFileReopen() throws Exception {
    var source = source();
    var id = UUID.randomUUID();
    String account = "56e25ff5-0d7c-4b46-a645-c3431055bdc2";
    try (var context = open(source, true, 0)) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      var origin = IdentityCreationJournalRestartTest.origin();
      var execution = journal.begin(id, origin, "owned");
      var receipt =
          new KeycloakTaskCommands.CreationResult(
              id, account, "original-receipt", "OPEN", execution.claim());
      journal.created(receipt, origin, execution);
      tx(context)
          .executeWithoutResult(
              status -> {
                journal.acquireLocalSaga(receipt);
                var writer = context.getBean(IdentityCreationEffectWriter.class);
                var effectId = UUID.randomUUID();
                writer.started(effectId, receipt, 42L, "APPOINTMENT_CONSULTANT", account);
                writer.acknowledge(effectId, "CREATED", account);
                status.setRollbackOnly();
              });
    }
    try (var context = open(source, false, 61)) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      var row = journal.reconciliationRequired().getFirst();
      journal.recovered(
          row,
          new KeycloakTaskCommands.RecoveryResult(
              id, account, "original-receipt", "RECOVERY_CLAIMED"));
      context.getBean(IdentityCreationEffects.class).clean(id);
      verify(
              context.getBean(
                  de.caritas.cob.userservice.api.service.appointment.AppointmentService.class))
          .deleteOwnedCreationConsultant(account, 42L);
      assertThat(journal.attempt(id).getStatus()).isEqualTo("LOCAL_CLEANUP_REQUESTED");
      verifyNoInteractions(context.getBean(MatrixSynapseService.class));
    }
  }

  private void captureByAbruptDeath(DataSource source, UUID id) throws Exception {
    try (var context = open(source, true, 0)) {
      create(context.getBean(IdentityCreationJournalWriter.class), id, true);
      context
          .getBean(org.springframework.jdbc.core.JdbcTemplate.class)
          .execute("CREATE TABLE local_effect_test(id VARCHAR(36))");
    }
    String url = ((JdbcDataSource) source).getURL();
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                CrashAfterAcknowledgment.class.getName(),
                url,
                id.toString())
            .redirectErrorStream(true)
            .redirectOutput(directory.resolve("child-process.log").toFile())
            .start();
    try {
      assertThat(process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).isEqualTo(23);
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
    try (var context = open(source, false, 1)) {
      assertThat(
              context
                  .getBean(org.springframework.jdbc.core.JdbcTemplate.class)
                  .queryForObject("SELECT COUNT(*) FROM local_effect_test", Integer.class))
          .isZero();
    }
  }

  public static final class CrashAfterAcknowledgment {
    public static void main(String[] arguments) throws Exception {
      var source = new JdbcDataSource();
      source.setURL(arguments[0]);
      source.setUser("sa");
      var id = UUID.fromString(arguments[1]);
      try (var context =
          IdentityCreationJournalRestartTest.open(
              source, false, Clock.fixed(START.plusSeconds(1), ZoneOffset.UTC))) {
        var journal = context.getBean(IdentityCreationJournalWriter.class);
        var row = journal.attempt(id);
        var receipt =
            new KeycloakTaskCommands.CreationResult(
                id,
                row.getAccountId(),
                row.getCreationProof(),
                "OPEN",
                UUID.fromString(row.getExecutionClaim()));
        tx(context)
            .executeWithoutResult(
                s -> {
                  var scope = context.getBean(IdentityCreationEffects.class).capture(receipt);
                  var user = scope.user();
                  user.started("@owned:matrix.example.com");
                  user.created("@owned:matrix.example.com");
                  var roomResponse =
                      new de.caritas.cob.userservice.api.adapters.matrix.dto
                          .MatrixCreateRoomResponseDTO();
                  roomResponse.setRoomId("!owned:matrix.example.com");
                  var matrix = context.getBean(MatrixSynapseService.class);
                  try {
                    when(matrix.createRoom(
                            "private holding room", "fresh-private-alias", "synthetic-token"))
                        .thenReturn(org.springframework.http.ResponseEntity.ok(roomResponse));
                    new de.caritas.cob.userservice.api.adapters.matrix.MatrixSessionRoomGateway(
                            matrix,
                            new de.caritas.cob.userservice.api.adapters.matrix.config
                                .MatrixConfig())
                        .createOwnedPrivateRoom(
                            "private holding room",
                            "fresh-private-alias",
                            "synthetic-token",
                            scope.privateRoom());
                  } catch (
                      de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException e) {
                    throw new IllegalStateException(e);
                  }
                  context
                      .getBean(org.springframework.jdbc.core.JdbcTemplate.class)
                      .update("INSERT INTO local_effect_test(id) VALUES(?)", id.toString());
                  Runtime.getRuntime()
                      .halt(
                          23); // real process death while parent lock/local writes are uncommitted
                });
      }
    }
  }

  private void capture(DataSource source, UUID id, String mode, boolean room) throws Exception {
    try (var context = open(source, true, 0)) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      var receipt = create(journal, id);
      tx(context)
          .executeWithoutResult(
              s -> {
                var scope = context.getBean(IdentityCreationEffects.class).capture(receipt);
                var user = scope.user();
                user.started("@owned:matrix.example.com");
                switch (mode) {
                  case "CREATED" -> user.created("@owned:matrix.example.com");
                  case "REACTIVATED_FROM_DEACTIVATED" ->
                      user.restoreDeactivated("@owned:matrix.example.com");
                  case "FOREIGN" ->
                      assertThatThrownBy(() -> user.created("@foreign:matrix.example.com"))
                          .isInstanceOf(
                              org.springframework.security.access.AccessDeniedException.class);
                  default -> {}
                }
                if (room) {
                  var effect = scope.privateRoom();
                  effect.started("fresh-private-alias");
                  effect.created("!owned:matrix.example.com");
                }
                s.setRollbackOnly(); // local saga dies after independent acknowledgment, before
                // completion
              });
    }
  }

  private static KeycloakTaskCommands.CreationResult create(
      IdentityCreationJournalWriter journal, UUID id) {
    return create(journal, id, false);
  }

  private static KeycloakTaskCommands.CreationResult create(
      IdentityCreationJournalWriter journal, UUID id, boolean privateRoom) {
    var origin =
        privateRoom
            ? IdentityCreationOrigin.checkedRegistration(
                de.caritas.cob.userservice.api.adapters.web.dto.UserDTO.builder()
                    .username("owned")
                    .password("test-only-password")
                    .termsAccepted("true")
                    .postcode("12345")
                    .consultingType("1")
                    .agencyId(8L)
                    .build(),
                new de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO()
                    .id(8L)
                    .tenantId(42L)
                    .consultingType(1),
                42L)
            : IdentityCreationJournalRestartTest.origin();
    var execution = journal.begin(id, origin, "owned");
    var receipt =
        new KeycloakTaskCommands.CreationResult(
            id, "owned-keycloak-id", "original-receipt", "OPEN", execution.claim());
    journal.created(receipt, origin, execution);
    return receipt;
  }

  private static void recover(IdentityCreationJournalWriter journal, UUID id) {
    var row =
        journal.reconciliationRequired().stream()
            .filter(r -> r.getId().equals(id.toString()))
            .findFirst()
            .orElseThrow();
    journal.recovered(
        row,
        new KeycloakTaskCommands.RecoveryResult(
            id, "owned-keycloak-id", "original-receipt", "RECOVERY_CLAIMED"));
  }

  private static TransactionTemplate tx(
      org.springframework.context.annotation.AnnotationConfigApplicationContext c) {
    return new TransactionTemplate(c.getBean(PlatformTransactionManager.class));
  }

  private org.springframework.context.annotation.AnnotationConfigApplicationContext open(
      DataSource s, boolean migrate, long seconds) throws Exception {
    return IdentityCreationJournalRestartTest.open(
        s, migrate, Clock.fixed(START.plusSeconds(seconds), ZoneOffset.UTC));
  }

  private DataSource source() {
    var source = new JdbcDataSource();
    source.setURL(
        "jdbc:h2:file:"
            + directory.resolve("effects")
            + ";MODE=MariaDB;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0");
    source.setUser("sa");
    return source;
  }
}
