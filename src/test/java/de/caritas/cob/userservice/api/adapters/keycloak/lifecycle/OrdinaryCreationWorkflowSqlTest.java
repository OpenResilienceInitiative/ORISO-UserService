package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixIdentifierRedactor;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixMediaClient;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixRoomClient;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
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
    return restarted(mock(AppointmentService.class));
  }

  private OrdinaryAccountCreation restarted(AppointmentService appointments) {
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
        appointments,
        mock(AuthenticatedUser.class),
        "https://issuer.example/realms/oriso",
        Set.of("human-admin"),
        Map.of("/human-roles/consultant", Set.of("consultant")),
        mock(OrdinaryCreationTenantResolver.class));
  }

  @Test
  void definiteMatrixInUseForConsultantReleasesOnlyItsUncreatedEffect() {
    matrixRegistrationRefusal(false, true);
  }

  @Test
  void definiteMatrixInUseForAskerReleasesOnlyItsUncreatedEffect() {
    matrixRegistrationRefusal(true, true);
  }

  @Test
  void unknownMatrixConsultantFailureRetainsUncertainEffectWithoutCleanup() {
    matrixRegistrationRefusal(false, false);
  }

  @Test
  void unknownMatrixAskerFailureRetainsUncertainEffectWithoutCleanup() {
    matrixRegistrationRefusal(true, false);
  }

  private void matrixRegistrationRefusal(boolean registration, boolean inUse) {
    var rest = new RestTemplate();
    var server = MockRestServiceServer.bindTo(rest).build();
    String url = "https://matrix.fixture.invalid/_synapse/admin/v1/register";
    server
        .expect(requestTo(url))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"nonce\":\"fixture-nonce\"}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(url))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(inUse ? HttpStatus.BAD_REQUEST : HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(inUse ? "{\"errcode\":\"M_USER_IN_USE\"}" : "{\"errcode\":\"M_UNKNOWN\"}"));
    var config = new MatrixConfig();
    config.setApiUrl("https://matrix.fixture.invalid");
    config.setServerName("matrix.fixture.invalid");
    config.setRegistrationSharedSecret("synthetic-matrix-registration-secret");
    var matrix =
        new MatrixSynapseService(
            config,
            rest,
            new RestTemplate(),
            mock(MatrixRoomClient.class),
            mock(MatrixMediaClient.class),
            mock(MatrixIdentifierRedactor.class));
    var cleanup = mock(JdbcOwnedLifecycleEffects.CleanupOperations.class);
    var effects = new JdbcOwnedLifecycleEffects(jdbc, manager, cleanup, codec);
    var dispatcher = new AccountLifecycleDispatcher(remote, journal, effects, codec);
    var coordinator = new AccountLifecycleCoordinator(remote, journal, dispatcher, codec);
    var ordinary =
        new OrdinaryAccountCreation(
            coordinator,
            journal,
            remote,
            effects,
            codec,
            matrix,
            config,
            mock(AppointmentService.class),
            mock(AuthenticatedUser.class),
            "https://issuer.example/realms/oriso",
            Set.of("human-admin"),
            Map.of("/human-roles/consultant", Set.of("consultant")),
            mock(OrdinaryCreationTenantResolver.class));
    var asker =
        new OrdinaryRegistrationLifecycle(
            coordinator, journal, remote, effects, matrix, config, "/human-roles/asker");
    var dto = new UserDTO();
    dto.setUsername(new UsernameTranscoder().encodeUsername("synthetic"));
    dto.setPassword("synthetic-password");
    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () -> {
              if (registration)
                asker.register(dto, 7L, session -> asker.createMatrixUser(session, "synthetic"));
              else
                ordinary.create(
                    scope,
                    account,
                    pending -> ordinary.createChatAccount(pending, "synthetic", "Synthetic"));
            });
    server.verify(); // Both actual HTTP responses must precede every outcome assertion.
    UUID id =
        UUID.fromString(
            jdbc.queryForObject("SELECT id FROM identity_lifecycle_attempt", String.class));
    assertEquals(
        "RECOVERY_REQUESTED",
        jdbc.queryForObject("SELECT status FROM identity_lifecycle_attempt", String.class));
    when(remote.recover(eq(id), any()))
        .thenAnswer(
            call -> {
              RecoverRequest request = call.getArgument(1);
              return new AttemptResult(
                  id,
                  "owned-account",
                  "RECOVERY_CLAIMED",
                  new ExecutionRef(request.recoveryId(), request.expectedGeneration() + 1),
                  "proof",
                  "recovery",
                  null);
            });
    when(remote.compensate(eq(id), any()))
        .thenAnswer(
            call -> {
              CompensateRequest request = call.getArgument(1);
              return new AttemptResult(
                  id,
                  "owned-account",
                  "COMPENSATED",
                  request.execution(),
                  "proof",
                  null,
                  "terminal");
            });
    dispatcher.dispatch(id);
    if (inUse) {
      var recoveryFailures = new ArrayList<RuntimeException>();
      for (int step = 0; step < 2; step++) {
        try {
          dispatcher.dispatch(id);
        } catch (RuntimeException unfinished) {
          recoveryFailures.add(unfinished);
        }
      }
      var rejected =
          failure instanceof CustomValidationHttpStatusException validation ? validation : null;
      assertAll(
          () -> assertInstanceOf(CustomValidationHttpStatusException.class, failure),
          () ->
              assertEquals(HttpStatus.CONFLICT, rejected == null ? null : rejected.getHttpStatus()),
          () ->
              assertEquals(
                  "USERNAME_NOT_AVAILABLE",
                  rejected == null ? null : rejected.getCustomHttpHeaders().getFirst("X-Reason")),
          () ->
              assertEquals(
                  "NO_EFFECT",
                  jdbc.queryForObject("SELECT state FROM identity_lifecycle_effect", String.class)),
          () ->
              assertEquals(
                  "COMPENSATED",
                  jdbc.queryForObject(
                      "SELECT status FROM identity_lifecycle_attempt", String.class)),
          () -> assertTrue(recoveryFailures.isEmpty()));
      verify(remote).compensate(eq(id), any());
    } else {
      assertInstanceOf(IllegalStateException.class, failure);
      assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(id));
      assertEquals(
          "STARTED",
          jdbc.queryForObject("SELECT state FROM identity_lifecycle_effect", String.class));
      assertEquals(
          "LOCAL_CLEANUP_REQUESTED",
          jdbc.queryForObject("SELECT status FROM identity_lifecycle_attempt", String.class));
      verify(remote, never()).compensate(any(), any());
    }
    verifyNoInteractions(cleanup);
    verify(remote, never()).commit(any(), any());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM local_created", Integer.class));
  }

  @Test
  void ownedAppointmentUsesActualHttpOutsideSqlAndResumesTheOriginalSaga() throws Exception {
    when(remote.commit(any(), any()))
        .thenAnswer(
            call -> {
              CommitRequest request = call.getArgument(1);
              return new AttemptResult(
                  call.getArgument(0),
                  "owned-account",
                  "COMMITTED",
                  request.execution(),
                  "proof",
                  null,
                  "terminal");
            });
    try (var fixture = ownedAppointment(200)) {
      var ordinary = restarted(fixture.service());
      assertDoesNotThrow(
          () ->
              new TransactionTemplate(manager)
                  .execute(
                      status ->
                          ordinary.create(
                              scope,
                              account,
                              pending -> {
                                jdbc.update(
                                    "INSERT INTO local_created(id) VALUES (?)",
                                    pending.accountId());
                                assertTrue(
                                    org.springframework.transaction.support
                                        .TransactionSynchronizationManager
                                        .isActualTransactionActive());
                                ordinary.createAppointment(pending, appointmentResponse());
                                assertTrue(
                                    org.springframework.transaction.support
                                        .TransactionSynchronizationManager
                                        .isActualTransactionActive());
                                return "done";
                              })));
      assertAll(
          () -> assertEquals(1, fixture.requests().get()),
          () -> assertEquals(Boolean.FALSE, fixture.transactionAtTransport().get()),
          () -> assertEquals(0, fixture.visibleLocalRows().get()),
          () -> assertEquals("owned-account", fixture.receivedAccount().get()),
          () -> assertEquals("7", fixture.receivedTenant().get()),
          () ->
              assertEquals(
                  "ACKNOWLEDGED",
                  jdbc.queryForObject("SELECT state FROM identity_lifecycle_effect", String.class)),
          () ->
              assertEquals(
                  "COMMITTED",
                  jdbc.queryForObject(
                      "SELECT status FROM identity_lifecycle_attempt", String.class)),
          () ->
              assertEquals(
                  1, jdbc.queryForObject("SELECT COUNT(*) FROM local_created", Integer.class)));
      verify(remote).commit(any(), any());
    }
  }

  @Test
  void unknownOwnedAppointmentHttpOutcomeKeepsTheDurableEffectAndRollsBackLocalWrites()
      throws Exception {
    try (var fixture = ownedAppointment(503)) {
      var ordinary = restarted(fixture.service());
      assertThrows(
          IllegalStateException.class,
          () ->
              ordinary.create(
                  scope,
                  account,
                  pending -> {
                    jdbc.update("INSERT INTO local_created(id) VALUES (?)", pending.accountId());
                    ordinary.createAppointment(pending, appointmentResponse());
                    return "done";
                  }));
      assertAll(
          () -> assertEquals(1, fixture.requests().get()),
          () -> assertEquals(Boolean.FALSE, fixture.transactionAtTransport().get()),
          () ->
              assertEquals(
                  "STARTED",
                  jdbc.queryForObject("SELECT state FROM identity_lifecycle_effect", String.class)),
          () ->
              assertEquals(
                  0, jdbc.queryForObject("SELECT COUNT(*) FROM local_created", Integer.class)));
      verify(remote, never()).commit(any(), any());
      verify(remote, never()).compensate(any(), any());
    }
  }

  private de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO
      appointmentResponse() {
    var response = new de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO();
    var consultant = new de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO();
    consultant.setId("owned-account");
    response.setEmbedded(consultant);
    return response;
  }

  private OwnedAppointmentHttp ownedAppointment(int responseStatus) throws Exception {
    var requests = new java.util.concurrent.atomic.AtomicInteger();
    var transaction = new AtomicReference<Boolean>();
    var visibleRows = new java.util.concurrent.atomic.AtomicInteger(-1);
    var receivedAccount = new AtomicReference<String>();
    var receivedTenant = new AtomicReference<String>();
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/consultants",
        exchange -> {
          try (exchange) {
            requests.incrementAndGet();
            receivedTenant.set(exchange.getRequestHeaders().getFirst("tenantId"));
            receivedAccount.set(
                new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(exchange.getRequestBody())
                    .path("id")
                    .asText());
            visibleRows.set(
                jdbc.queryForObject("SELECT COUNT(*) FROM local_created", Integer.class));
            byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
          }
        });
    server.start();
    try {
      var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
      requestFactory.setConnectTimeout(2000);
      requestFactory.setReadTimeout(5000);
      var rest = new RestTemplate(requestFactory);
      rest.getInterceptors()
          .add(
              (request, body, execution) -> {
                transaction.set(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                return execution.execute(request, body);
              });
      var factory =
          new de.caritas.cob.userservice.api.config.apiclient
              .AppointmentConsultantServiceApiControllerFactory();
      org.springframework.test.util.ReflectionTestUtils.setField(factory, "restTemplate", rest);
      org.springframework.test.util.ReflectionTestUtils.setField(
          factory, "appointmentServiceApiUrl", "http://127.0.0.1:" + server.getAddress().getPort());
      var config = mock(de.caritas.cob.userservice.api.port.out.IdentityClientConfig.class);
      when(config.isFiveActorMode()).thenReturn(true);
      when(config.taskAccessToken(
              de.caritas.cob.userservice.api.config.auth.TaskPurpose.APPOINTMENT_SYNC))
          .thenReturn("synthetic-appointment-token");
      var headers =
          mock(de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier.class);
      when(headers.getKeycloakAndCsrfHttpHeaders("synthetic-appointment-token"))
          .thenReturn(new org.springframework.http.HttpHeaders());
      var service =
          new AppointmentService(
              factory,
              mock(
                  de.caritas.cob.userservice.api.config.apiclient
                      .AppointmentAgencyServiceApiControllerFactory.class),
              mock(
                  de.caritas.cob.userservice.api.config.apiclient
                      .AppointmentAskerServiceApiControllerFactory.class),
              headers,
              mock(de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier.class),
              mock(de.caritas.cob.userservice.api.port.out.IdentityAuthentication.class),
              config);
      org.springframework.test.util.ReflectionTestUtils.setField(
          service, "appointmentFeatureEnabled", true);
      var proxy = new org.springframework.aop.framework.ProxyFactory(service);
      proxy.setProxyTargetClass(true);
      proxy.addAdvice(
          new org.springframework.transaction.interceptor.TransactionInterceptor(
              manager,
              new org.springframework.transaction.annotation
                  .AnnotationTransactionAttributeSource()));
      return new OwnedAppointmentHttp(
          (AppointmentService) proxy.getProxy(),
          server,
          requests,
          transaction,
          visibleRows,
          receivedAccount,
          receivedTenant);
    } catch (Exception | Error failure) {
      server.stop(0);
      throw failure;
    }
  }

  private record OwnedAppointmentHttp(
      AppointmentService service,
      com.sun.net.httpserver.HttpServer server,
      java.util.concurrent.atomic.AtomicInteger requests,
      AtomicReference<Boolean> transactionAtTransport,
      java.util.concurrent.atomic.AtomicInteger visibleLocalRows,
      AtomicReference<String> receivedAccount,
      AtomicReference<String> receivedTenant)
      implements AutoCloseable {
    public void close() {
      server.stop(0);
    }
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
