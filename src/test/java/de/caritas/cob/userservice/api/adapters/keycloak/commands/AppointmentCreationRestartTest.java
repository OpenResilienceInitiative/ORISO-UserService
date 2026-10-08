package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.model.IdentityCreationAttempt;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual POST/DELETE, abrupt JVM death, and file reopen at the unfinished owned creation seam. */
class AppointmentCreationRestartTest {
  @TempDir Path directory;
  static final Instant START = Instant.parse("2026-10-08T10:00:00Z");
  static final String ACCOUNT = "56e25ff5-0d7c-4b46-a645-c3431055bdc2";
  static final String FOREIGN = "cd113aaa-11e9-4df0-ab01-58e802da7aac";

  @Test
  void acknowledgedAppointmentSurvivesHaltAndCleanupRetriesWithoutLocalConsultant()
      throws Exception {
    try (var remote = new Remote()) {
      var source = source();
      var id = UUID.randomUUID();
      initialize(source, id, remote.url());
      var process =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-cp",
                  System.getProperty("java.class.path"),
                  CrashAfterAppointment.class.getName(),
                  source.getURL(),
                  id.toString(),
                  remote.url())
              .redirectErrorStream(true)
              .redirectOutput(directory.resolve("appointment-child.log").toFile())
              .start();
      try {
        assertThat(process.waitFor(45, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isEqualTo(23);
      } finally {
        if (process.isAlive()) process.destroyForcibly();
      }
      assertThat(remote.records).contains(ACCOUNT, FOREIGN);
      assertThat(remote.calls).containsExactly("POST /consultants 42 APPOINTMENT_SYNC");
      try (var context = open(source, false, 61, remote.url())) {
        assertThat(
                context
                    .getBean(JdbcTemplate.class)
                    .queryForObject("SELECT COUNT(*) FROM local_appointment_test", Integer.class))
            .isZero();
        recover(context, id);
        remote.failDelete.set(true);
        assertThatThrownBy(() -> context.getBean(IdentityCreationEffects.class).clean(id))
            .isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
        assertThat(context.getBean(IdentityCreationJournalWriter.class).attempt(id).getStatus())
            .isEqualTo("LOCAL_CLEANUP_REQUESTED");
        assertThat(remote.records).contains(ACCOUNT, FOREIGN);
      }
      remote.failDelete.set(false);
      try (var context = open(source, false, 62, remote.url())) {
        context.getBean(IdentityCreationEffects.class).clean(id);
        assertThat(remote.records).containsExactly(FOREIGN);
        assertThat(context.getBean(IdentityCreationJournalWriter.class).attempt(id).getStatus())
            .isEqualTo("LOCAL_CLEANUP_REQUESTED");
      }
      int calls = remote.calls.size();
      try (var context = open(source, false, 63, remote.url())) {
        context.getBean(IdentityCreationEffects.class).clean(id);
        assertThat(remote.calls).hasSize(calls);
      }
      assertThat(remote.calls)
          .containsExactly(
              "POST /consultants 42 APPOINTMENT_SYNC",
              "DELETE /consultants/" + ACCOUNT + " 42 APPOINTMENT_CLEANUP",
              "DELETE /consultants/" + ACCOUNT + " 42 APPOINTMENT_CLEANUP");
    }
  }

  @Test
  void unacknowledgedRemoteOutcomeRemainsPendingAndNeverDeletesGuessedAccount() throws Exception {
    try (var remote = new Remote()) {
      var source = source();
      var id = UUID.randomUUID();
      initialize(source, id, remote.url());
      remote.failCreate.set(true);
      try (var context = open(source, false, 1, remote.url())) {
        var row = context.getBean(IdentityCreationJournalWriter.class).attempt(id);
        tx(context)
            .executeWithoutResult(
                status -> {
                  var scope = context.getBean(IdentityCreationEffects.class).capture(receipt(row));
                  assertThatThrownBy(
                          () ->
                              context
                                  .getBean(AppointmentService.class)
                                  .createOwnedConsultant(
                                      response(ACCOUNT), 42L, scope.appointmentConsultant()))
                      .isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
                  status.setRollbackOnly();
                });
      }
      try (var context = open(source, false, 61, remote.url())) {
        recover(context, id);
        assertThatThrownBy(() -> context.getBean(IdentityCreationEffects.class).clean(id))
            .isInstanceOf(IllegalStateException.class);
        assertThat(context.getBean(IdentityCreationJournalWriter.class).attempt(id).getStatus())
            .isEqualTo("LOCAL_CLEANUP_REQUESTED");
      }
      assertThat(remote.records).contains(ACCOUNT, FOREIGN);
      assertThat(remote.calls).containsExactly("POST /consultants 42 APPOINTMENT_SYNC");
    }
  }

  @Test
  void disabledCleanupLeavesAcknowledgedAppointmentPendingUntilItsTaskIsAvailable()
      throws Exception {
    try (var remote = new Remote()) {
      var source = source();
      var id = UUID.randomUUID();
      initialize(source, id, remote.url());
      try (var context = open(source, false, 1, remote.url())) {
        var row = context.getBean(IdentityCreationJournalWriter.class).attempt(id);
        tx(context)
            .executeWithoutResult(
                status -> {
                  var scope = context.getBean(IdentityCreationEffects.class).capture(receipt(row));
                  context
                      .getBean(AppointmentService.class)
                      .createOwnedConsultant(response(ACCOUNT), 42L, scope.appointmentConsultant());
                  status.setRollbackOnly();
                });
      }
      try (var context = open(source, false, 61, remote.url())) {
        recover(context, id);
        org.springframework.test.util.ReflectionTestUtils.setField(
            context.getBean(AppointmentService.class), "appointmentFeatureEnabled", false);
        assertThatThrownBy(() -> context.getBean(IdentityCreationEffects.class).clean(id))
            .isInstanceOf(IllegalStateException.class);
        assertThat(context.getBean(IdentityCreationJournalWriter.class).attempt(id).getStatus())
            .isEqualTo("LOCAL_CLEANUP_REQUESTED");
        assertThat(remote.calls).containsExactly("POST /consultants 42 APPOINTMENT_SYNC");
        assertThat(remote.records).contains(ACCOUNT, FOREIGN);
      }
      try (var context = open(source, false, 62, remote.url())) {
        context.getBean(IdentityCreationEffects.class).clean(id);
      }
      assertThat(remote.records).containsExactly(FOREIGN);
    }
  }

  @Test
  void foreignAccountOrTenantCannotReachAppointmentTransport() throws Exception {
    try (var remote = new Remote()) {
      var source = source();
      var id = UUID.randomUUID();
      initialize(source, id, remote.url());
      try (var context = open(source, false, 1, remote.url())) {
        var row = context.getBean(IdentityCreationJournalWriter.class).attempt(id);
        tx(context)
            .executeWithoutResult(
                status -> {
                  var scope = context.getBean(IdentityCreationEffects.class).capture(receipt(row));
                  var api = context.getBean(AppointmentService.class);
                  assertThatThrownBy(
                          () ->
                              api.createOwnedConsultant(
                                  response(FOREIGN), 42L, scope.appointmentConsultant()))
                      .isInstanceOf(
                          org.springframework.security.access.AccessDeniedException.class);
                  assertThatThrownBy(
                          () ->
                              api.createOwnedConsultant(
                                  response(ACCOUNT), 99L, scope.appointmentConsultant()))
                      .isInstanceOf(
                          org.springframework.security.access.AccessDeniedException.class);
                  status.setRollbackOnly();
                });
      }
      assertThat(remote.calls).isEmpty();
      assertThat(remote.records).containsExactly(FOREIGN);
    }
  }

  public static final class CrashAfterAppointment {
    public static void main(String[] args) throws Exception {
      var source = new JdbcDataSource();
      source.setURL(args[0]);
      source.setUser("sa");
      try (var context = open(source, false, 1, args[2])) {
        var row =
            context.getBean(IdentityCreationJournalWriter.class).attempt(UUID.fromString(args[1]));
        tx(context)
            .executeWithoutResult(
                status -> {
                  var scope = context.getBean(IdentityCreationEffects.class).capture(receipt(row));
                  context
                      .getBean(AppointmentService.class)
                      .createOwnedConsultant(response(ACCOUNT), 42L, scope.appointmentConsultant());
                  context
                      .getBean(JdbcTemplate.class)
                      .update("INSERT INTO local_appointment_test(id) VALUES(?)", ACCOUNT);
                  Runtime.getRuntime().halt(23);
                });
      }
    }
  }

  static ConsultantAdminResponseDTO response(String id) {
    return new ConsultantAdminResponseDTO()
        .embedded(
            new ConsultantDTO()
                .id(id)
                .firstname("Test")
                .lastname("Consultant")
                .email("test@example.com"));
  }

  static KeycloakTaskCommands.CreationResult receipt(IdentityCreationAttempt row) {
    return new KeycloakTaskCommands.CreationResult(
        UUID.fromString(row.getId()),
        row.getAccountId(),
        row.getCreationProof(),
        "OPEN",
        UUID.fromString(row.getExecutionClaim()));
  }

  static void initialize(JdbcDataSource source, UUID id, String url) throws Exception {
    try (var context = open(source, true, 0, url)) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      var origin = IdentityCreationJournalRestartTest.origin();
      var execution = journal.begin(id, origin, "owned");
      journal.created(
          new KeycloakTaskCommands.CreationResult(
              id, ACCOUNT, "original-receipt", "OPEN", execution.claim()),
          origin,
          execution);
      context
          .getBean(JdbcTemplate.class)
          .execute("CREATE TABLE local_appointment_test(id VARCHAR(36))");
    }
  }

  static void recover(AnnotationConfigApplicationContext context, UUID id) {
    var journal = context.getBean(IdentityCreationJournalWriter.class);
    var row =
        journal.reconciliationRequired().stream()
            .filter(r -> r.getId().equals(id.toString()))
            .findFirst()
            .orElseThrow();
    journal.recovered(
        row,
        new KeycloakTaskCommands.RecoveryResult(
            id, ACCOUNT, "original-receipt", "RECOVERY_CLAIMED"));
  }

  static AnnotationConfigApplicationContext open(
      JdbcDataSource s, boolean migrate, long seconds, String url) throws Exception {
    return IdentityCreationJournalRestartTest.open(
        s, migrate, Clock.fixed(START.plusSeconds(seconds), ZoneOffset.UTC), false, url);
  }

  static TransactionTemplate tx(AnnotationConfigApplicationContext context) {
    return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
  }

  JdbcDataSource source() {
    var s = new JdbcDataSource();
    s.setURL(
        "jdbc:h2:file:"
            + directory.resolve("appointments")
            + ";MODE=MariaDB;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0");
    s.setUser("sa");
    return s;
  }

  static final class Remote implements AutoCloseable {
    final Set<String> records = ConcurrentHashMap.newKeySet();
    final List<String> calls = new CopyOnWriteArrayList<>();
    final AtomicBoolean failDelete = new AtomicBoolean(), failCreate = new AtomicBoolean();
    final HttpServer server;

    Remote() throws Exception {
      records.add(FOREIGN);
      server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/consultants",
          exchange -> {
            String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
            calls.add(
                method
                    + " "
                    + path
                    + " "
                    + exchange.getRequestHeaders().getFirst("tenantId")
                    + " "
                    + exchange
                        .getRequestHeaders()
                        .getFirst("Authorization")
                        .replace("Bearer ", ""));
            int code;
            if (method.equals("POST")) {
              var body =
                  new com.fasterxml.jackson.databind.ObjectMapper()
                      .readTree(exchange.getRequestBody());
              if (!ACCOUNT.equals(body.path("id").asText())
                  || !"42".equals(exchange.getRequestHeaders().getFirst("tenantId"))) code = 403;
              else {
                records.add(ACCOUNT);
                code = failCreate.get() ? 503 : 200;
              }
            } else if (method.equals("DELETE")
                && path.equals("/consultants/" + ACCOUNT)
                && "42".equals(exchange.getRequestHeaders().getFirst("tenantId"))) {
              if (failDelete.get()) code = 503;
              else {
                records.remove(ACCOUNT);
                code = 204;
              }
            } else code = 403;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (code == 204) exchange.sendResponseHeaders(code, -1);
            else {
              byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(code, body.length);
              exchange.getResponseBody().write(body);
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
