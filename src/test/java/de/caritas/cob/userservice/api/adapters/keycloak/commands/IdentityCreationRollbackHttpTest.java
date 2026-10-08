package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.adapters.matrix.*;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.UserAgencyService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.*;
import de.caritas.cob.userservice.api.workflow.delete.service.*;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

/** Controlled creation rollback preserves a reused inactive identity, including a local retry. */
class IdentityCreationRollbackHttpTest {
  @TempDir Path directory;
  private static final Instant START = Instant.parse("2026-10-08T10:00:00Z");

  @Test
  void rollbackAndLocalFailureNeverErasePriorInactiveIdentityOrEnrollItInOrdinaryDeletion()
      throws Exception {
    var requests = new CopyOnWriteArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String method = exchange.getRequestMethod();
          String path = exchange.getRequestURI().getPath();
          String body =
              new String(
                  exchange.getRequestBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8);
          if (path.contains("/users/") && !method.equals("GET"))
            requests.add(
                method + " " + path + " " + (body.contains("password") ? "password-update" : body));
          int status = 200;
          String response = "{}";
          if (path.endsWith("/register")) {
            if (method.equals("GET")) response = "{\"nonce\":\"synthetic-nonce\"}";
            else {
              status = 400;
              response = "{\"errcode\":\"M_USER_IN_USE\"}";
            }
          } else if (path.endsWith("/login"))
            response = "{\"access_token\":\"synthetic-admin-token\"}";
          else if (path.contains("/users/") && method.equals("GET"))
            response = "{\"deactivated\":true}";
          byte[] reply = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, reply.length);
          exchange.getResponseBody().write(reply);
          exchange.close();
        });
    server.start();
    var source = new JdbcDataSource();
    source.setURL(
        "jdbc:h2:file:"
            + directory.resolve("rollback")
            + ";MODE=MariaDB;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;NON_KEYWORDS=USER");
    var id = UUID.randomUUID();
    var commands = mock(IdentityProvisioningCommands.class);
    var config = new MatrixConfig();
    config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
    config.setServerName("matrix.example.com");
    config.setRegistrationSharedSecret("synthetic-registration-key");
    config.setAdminUsername("synthetic-admin");
    config.setAdminPassword("synthetic-password");
    var matrix =
        new MatrixSynapseService(
            config,
            new RestTemplate(),
            mock(RestTemplate.class),
            mock(MatrixRoomClient.class),
            mock(MatrixMediaClient.class),
            mock(MatrixIdentifierRedactor.class));
    de.caritas.cob.userservice.api.tenant.TenantContext.setCurrentTenant(
        IdentityCreationJournalRestartTest.origin().tenantId());
    try {
      try (var context =
          IdentityCreationJournalRestartTest.open(
              source, true, Clock.fixed(START, ZoneOffset.UTC), true)) {
        var journal = context.getBean(IdentityCreationJournalWriter.class);
        var origin = IdentityCreationJournalRestartTest.origin();
        var execution = journal.begin(id, origin, "owned");
        var receipt =
            new KeycloakTaskCommands.CreationResult(
                id, "owned-account", "original-receipt", "OPEN", execution.claim());
        journal.created(receipt, origin, execution);
        var consultants = context.getBean(ConsultantRepository.class);
        var consultant = consultant(receipt.accountId());
        tx(context).executeWithoutResult(s -> consultants.saveAndFlush(consultant));
        var jdbc = context.getBean(JdbcTemplate.class);
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS account_inactivity(identity_id VARCHAR(64) PRIMARY KEY,tenant_id BIGINT,status VARCHAR(20),attempts INT)");
        assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM consultant WHERE consultant_id=? AND tenant_id=42",
                    Integer.class,
                    receipt.accountId()))
            .isEqualTo(1);
        var failingRepository = mock(ConsultantRepository.class);
        doThrow(new IllegalStateException("injected local deletion outage"))
            .when(failingRepository)
            .delete(any(Consultant.class));
        var actions = actions(matrix, failingRepository);
        var effects = effects(context, matrix);
        var provisioning = new IdentityAccountProvisioning(commands, journal, effects);
        var deletion =
            new DeleteUserAccountService(
                mock(UserRepository.class),
                consultants,
                actions,
                mock(WorkflowErrorMailService.class),
                mock(DeletionLifecycleService.class));
        var rollback =
            new RollbackFacade(
                provisioning,
                mock(UserAgencyService.class),
                mock(SessionService.class),
                mock(UserService.class),
                deletion);
        tx(context)
            .executeWithoutResult(
                status -> {
                  journal.acquireLocalSaga(receipt);
                  var owned = effects.capture(receipt).user();
                  assertThat(
                          org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                              () ->
                                  matrix.createOwnedUserId(
                                      "owned", "synthetic-new-password", "Owned", owned)))
                      .isEqualTo("@owned:matrix.example.com");
                  assertThat(requests)
                      .containsExactly(
                          "PUT /_synapse/admin/v2/users/@owned:matrix.example.com {\"deactivated\":false}",
                          "PUT /_synapse/admin/v2/users/@owned:matrix.example.com password-update");
                  requests.clear();
                  assertThat(
                          jdbc.queryForObject(
                              "SELECT COUNT(*) FROM consultant WHERE consultant_id=? AND tenant_id=42",
                              Integer.class,
                              receipt.accountId()))
                      .isEqualTo(1);
                  var persisted = consultants.findById(receipt.accountId()).orElseThrow();
                  rollback.rollbackConsultantAccount(persisted);
                });
        assertThat(requests)
            .isEmpty(); // no ordinary erase/room/appointment call before journal cleanup
        assertThat(consultants.findById(receipt.accountId()).orElseThrow().getDeleteDate())
            .isNull();
        assertThat(consultants.findAllByDeleteDateNotNull()).isEmpty();
        assertThat(journal.attempt(id).getStatus()).isEqualTo("COMPENSATION_REQUESTED");
        var cleanup = cleanup(context, actions, effects);
        assertThatThrownBy(() -> tx(context).executeWithoutResult(s -> cleanup.clean(id)))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(commands); // local failure cannot falsely finish native compensation
        assertThat(journal.attempt(id).getStatus()).isEqualTo("COMPENSATION_REQUESTED");
      }
      try (var context =
          IdentityCreationJournalRestartTest.open(
              source, false, Clock.fixed(START.plusSeconds(1), ZoneOffset.UTC), true)) {
        var effects = effects(context, matrix);
        var cleanup =
            cleanup(context, actions(matrix, context.getBean(ConsultantRepository.class)), effects);
        tx(context).executeWithoutResult(s -> cleanup.clean(id));
        assertThat(context.getBean(ConsultantRepository.class).existsById("owned-account"))
            .isFalse();
        var journal = context.getBean(IdentityCreationJournalWriter.class);
        new IdentityAccountProvisioning(commands, journal, effects)
            .compensateCreatedAccount("owned-account");
        assertThat(journal.attempt(id).getStatus()).isEqualTo("COMPENSATED");
        verify(commands).compensate(any(), any());
      }
      assertThat(requests)
          .containsExactly(
              "PUT /_synapse/admin/v2/users/@owned:matrix.example.com {\"deactivated\":true}",
              "PUT /_synapse/admin/v2/users/@owned:matrix.example.com {\"deactivated\":true}");
    } finally {
      de.caritas.cob.userservice.api.tenant.TenantContext.clear();
      server.stop(0);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"COMMITTED", "COMPENSATED"})
  void foreignExistingAndTerminalTargetsAreRejectedBeforeLocalOrRemoteRollback(String terminal)
      throws Exception {
    var source = new JdbcDataSource();
    source.setURL(
        "jdbc:h2:file:" + directory.resolve("protected") + ";MODE=MariaDB;DB_CLOSE_ON_EXIT=FALSE");
    try (var context =
        IdentityCreationJournalRestartTest.open(source, true, Clock.fixed(START, ZoneOffset.UTC))) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      var id = UUID.randomUUID();
      var origin = IdentityCreationJournalRestartTest.origin();
      var execution = journal.begin(id, origin, "owned");
      var receipt =
          new KeycloakTaskCommands.CreationResult(
              id, "owned-account", "original-receipt", "OPEN", execution.claim());
      journal.created(receipt, origin, execution);
      var nativeCommands = mock(IdentityProvisioningCommands.class);
      var localDeletion = mock(DeleteUserAccountService.class);
      var rollback =
          new RollbackFacade(
              new IdentityAccountProvisioning(
                  nativeCommands, journal, context.getBean(IdentityCreationEffects.class)),
              mock(UserAgencyService.class),
              mock(SessionService.class),
              mock(UserService.class),
              localDeletion);
      assertThatThrownBy(
              () -> rollback.rollbackConsultantAccount(consultant("foreign-existing-account")))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      if (terminal.equals("COMMITTED"))
        tx(context).executeWithoutResult(s -> journal.prepareCommitInSaga(receipt, origin));
      else journal.request(receipt, origin, "COMPENSATION_REQUESTED");
      journal.finish(receipt, terminal);
      assertThatThrownBy(() -> rollback.rollbackConsultantAccount(consultant(receipt.accountId())))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      verifyNoInteractions(
          localDeletion, nativeCommands, context.getBean(MatrixSynapseService.class));
      assertThat(journal.attempt(id).getStatus()).isEqualTo(terminal);
    }
  }

  private static Consultant consultant(String id) {
    return Consultant.builder()
        .id(id)
        .tenantId(42L)
        .username("owned")
        .email("owned@example.invalid")
        .firstName("Owned")
        .lastName("Fixture")
        .encourage2fa(true)
        .magicLinkLoginEnabled(false)
        .notifyEnquiriesRepeating(true)
        .notifyNewChatMessageFromAdviceSeeker(true)
        .matrixUserId("@owned:matrix.example.com")
        .languageCode(com.neovisionaries.i18n.LanguageCode.de)
        .build();
  }

  private static IdentityCreationEffects effects(
      org.springframework.context.annotation.AnnotationConfigApplicationContext c,
      MatrixSynapseService matrix) {
    var effects =
        new IdentityCreationEffects(
            c.getBean(IdentityCreationJournalWriter.class),
            c.getBean(IdentityCreationEffectWriter.class),
            c.getBean(JdbcTemplate.class),
            matrix,
            mock(de.caritas.cob.userservice.api.service.appointment.AppointmentService.class));
    return (IdentityCreationEffects)
        c.getAutowireCapableBeanFactory().initializeBean(effects, "actualHttpEffects");
  }

  private static IdentityCreationLocalCleanup cleanup(
      org.springframework.context.annotation.AnnotationConfigApplicationContext c,
      ActionsRegistry actions,
      IdentityCreationEffects effects) {
    return new IdentityCreationLocalCleanup(
        c.getBean(IdentityCreationJournalWriter.class),
        mock(UserRepository.class),
        c.getBean(ConsultantRepository.class),
        mock(AdminRepository.class),
        mock(AdminAgencyRepository.class),
        actions,
        c.getBean(JdbcTemplate.class),
        effects);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static ActionsRegistry actions(
      MatrixSynapseService matrix, ConsultantRepository consultants) throws Exception {
    var dependencies = new HashMap<Class<?>, Object>();
    dependencies.put(MatrixSynapseService.class, matrix);
    dependencies.put(ConsultantRepository.class, consultants);
    var beans = new HashMap<String, ActionCommand>();
    for (var type :
        List.of(
            DeleteKeycloakConsultantAction.class,
            DeleteMatrixConsultantAction.class,
            DeleteDatabaseConsultantAgencyAction.class,
            DeleteChatAction.class,
            DeleteAppointmentServiceConsultantAction.class,
            DeleteCaseHandoverRequestsForConsultantAction.class,
            DeleteConsultantDraftMessagesAction.class,
            DeleteConsultantEventNotificationsAction.class,
            DeleteConsultantMessageEmailDeliveriesAction.class,
            DeleteDatabaseConsultantAction.class)) {
      var constructor = type.getConstructors()[0];
      var args =
          Arrays.stream(constructor.getParameterTypes())
              .map(
                  dependency -> dependencies.computeIfAbsent(dependency, org.mockito.Mockito::mock))
              .toArray();
      beans.put(type.getName(), (ActionCommand) constructor.newInstance(args));
    }
    var context = mock(ApplicationContext.class);
    when(context.getBeansOfType(ActionCommand.class)).thenReturn(beans);
    return new ActionsRegistry(context);
  }

  private static TransactionTemplate tx(
      org.springframework.context.annotation.AnnotationConfigApplicationContext c) {
    return new TransactionTemplate(c.getBean(PlatformTransactionManager.class));
  }
}
