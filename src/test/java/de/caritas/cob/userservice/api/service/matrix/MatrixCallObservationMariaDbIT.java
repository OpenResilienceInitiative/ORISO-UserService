package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.CallRoomOrigin;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.MatrixCallObservation;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallObservationRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallObservationService.Outcome;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MariaDBContainer;

/**
 * #1630/#1628 on the real database: parallel first registrations of one call, the end tombstone and
 * "no transaction open while Matrix is called", with the Liquibase schema on MariaDB.
 */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MatrixCallObservationService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MatrixCallObservationMariaDbIT {
  private static final MariaDBContainer<?> DATABASE =
      new MariaDBContainer<>("mariadb:10.11").withDatabaseName("userservice");

  static {
    DATABASE.start();
  }

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add(
        "spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MariaDBDialect");
    registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
    registry.add("spring.sql.init.mode", () -> "never");
    // Fail a blocked lock quickly instead of after MariaDB's default 50 s.
    registry.add(
        "spring.datasource.hikari.connection-init-sql",
        () -> "SET SESSION innodb_lock_wait_timeout = 5");
  }

  private static final String SOURCE = "!group-race:example";
  private static final String CREATOR = "@creator-race:example";
  private static final String OTHER = "@other-race:example";

  @Autowired private MatrixCallObservationService service;
  @Autowired private MatrixCallObservationRepository observations;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

  @MockitoBean private MatrixCallConversationResolver conversations;
  @MockitoBean private UserRepository users;
  @MockitoBean private ConsultantRepository consultants;
  @MockitoBean private MatrixSynapseService matrix;
  @MockitoBean private EventNotificationService notifications;
  @MockitoBean private MatrixCallLifecycleService lifecycle;

  private final List<String> matrixCallsInsideTransaction =
      Collections.synchronizedList(new ArrayList<>());
  private final MatrixCallConversation conversation =
      new MatrixCallConversation(SOURCE, 7L, null, 45L);
  private AuthenticatedUser actor;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM matrix_call_observation WHERE source_room_id = ?", SOURCE);
    actor = new AuthenticatedUser();
    actor.setUserId("creator");
    actor.setTenantId(7L);
    var creator = user("creator", CREATOR);
    when(users.findByUserIdAndDeleteDateIsNull("creator")).thenReturn(Optional.of(creator));
    when(users.findByMatrixUserIdAndDeleteDateIsNull(CREATOR)).thenReturn(Optional.of(creator));
    when(users.findByMatrixUserIdAndDeleteDateIsNull(OTHER))
        .thenReturn(Optional.of(user("other", OTHER)));
    when(consultants.findByMatrixUserIdAndDeleteDateIsNull(anyString()))
        .thenReturn(Optional.empty());
    when(lifecycle.isActiveRecipient(anyString())).thenReturn(true);
    when(conversations.resolveForWriting(SOURCE)).thenReturn(Optional.of(conversation));
    when(conversations.resolve(SOURCE)).thenReturn(Optional.of(conversation));
    when(conversations.lockCurrentForWriting(any())).thenReturn(true);
    when(matrix.getCallRoomMembers(SOURCE))
        .thenAnswer(outsideTransaction(Optional.of(List.of(CREATOR, OTHER))));
  }

  @Test
  void parallelFirstRegistrationsLeaveOneRowAndOneInvitation() throws Exception {
    String media = "!media-race:example";
    stubMediaRoom("race", media);
    // Both requests are inside their write transaction before either reads or inserts the row.
    var bothInside = new CyclicBarrier(2);
    when(conversations.lockCurrentForWriting(any()))
        .thenAnswer(
            invocation -> {
              bothInside.await(10, TimeUnit.SECONDS);
              return true;
            });

    var pool = Executors.newFixedThreadPool(2);
    Callable<Object> register =
        () -> {
          try {
            return service.register(actor, SOURCE, "race", media, true);
          } catch (RuntimeException failure) {
            return failure;
          }
        };
    List<Future<Object>> results =
        pool.invokeAll(List.of(register, register), 60, TimeUnit.SECONDS);
    pool.shutdown();
    var outcomes = new ArrayList<Object>();
    for (var result : results) outcomes.add(result.get());

    assertThat(outcomes).contains(Outcome.OK);
    for (Object outcome : outcomes) {
      if (outcome == Outcome.OK) continue;
      // What the controller answers 409 for; the client's retry then takes the renew path.
      assertThat(outcome)
          .as("loser of the race")
          .isInstanceOfAny(
              PessimisticLockingFailureException.class, DataIntegrityViolationException.class);
    }
    assertThat(rows("race")).isEqualTo(1);

    // The loser's retry renews the one row and invites nobody twice.
    org.mockito.Mockito.doReturn(true).when(conversations).lockCurrentForWriting(any());
    assertThat(service.register(actor, SOURCE, "race", media, true)).isEqualTo(Outcome.OK);
    assertThat(rows("race")).isEqualTo(1);
    verify(notifications, times(1))
        .createCallInvitationNotification(
            any(), eq("other"), eq(false), eq("creator"), eq("race"), eq(media), eq(true));
    assertThat(matrixCallsInsideTransaction).isEmpty();
  }

  @Test
  void anEndThatArrivesFirstLeavesATombstoneThatRejectsTheLateRegistration() {
    String media = "!media-late:example";
    stubMediaRoom("late", media);

    assertThat(service.end(actor, SOURCE, "late")).isEqualTo(Outcome.OK);
    // A second call ended early too: two tombstones without a media room may coexist.
    assertThat(service.end(actor, SOURCE, "late-2")).isEqualTo(Outcome.OK);

    assertThat(service.register(actor, SOURCE, "late", media, true)).isEqualTo(Outcome.NOT_FOUND);
    var row = observations.findBySourceRoomIdAndCallId(SOURCE, "late").orElseThrow();
    assertThat(row.getOrigin()).isEqualTo(MatrixCallObservation.ORIGIN_END_TOMBSTONE);
    assertThat(row.getMediaRoomId()).isNull();
    assertThat(row.getEndedAt()).isNotNull();
    assertThat(rows("late-2")).isEqualTo(1);
    verify(notifications, never())
        .createCallInvitationNotification(
            any(), anyString(), anyBoolean(), any(), any(), any(), anyBoolean());
    assertThat(matrixCallsInsideTransaction).isEmpty();
  }

  @Test
  void endingARegisteredCallRecordsTheEndOnce() {
    String media = "!media-end:example";
    stubMediaRoom("end", media);
    assertThat(service.register(actor, SOURCE, "end", media, false)).isEqualTo(Outcome.OK);

    assertThat(service.end(actor, SOURCE, "end")).isEqualTo(Outcome.OK);
    assertThat(service.end(actor, SOURCE, "end")).isEqualTo(Outcome.OK);

    var row = observations.findBySourceRoomIdAndCallId(SOURCE, "end").orElseThrow();
    assertThat(row.getEndedAt()).isNotNull();
    verify(notifications, times(1))
        .createCallEndedNotification(
            any(), eq("other"), eq(false), eq("end"), eq(media), any(Long.class), any(Long.class));
    assertThat(matrixCallsInsideTransaction).isEmpty();
  }

  private void stubMediaRoom(String callId, String media) {
    when(matrix.getCallRoomBinding(media, CREATOR))
        .thenAnswer(
            outsideTransaction(Optional.of(Map.of("call_id", callId, "source_room_id", SOURCE))));
    when(matrix.getCallRoomOrigin(media, CREATOR))
        .thenAnswer(outsideTransaction(Optional.of(new CallRoomOrigin(CREATOR, CREATOR))));
  }

  private <T> Answer<T> outsideTransaction(T value) {
    return invocation -> {
      if (TransactionSynchronizationManager.isActualTransactionActive()) {
        matrixCallsInsideTransaction.add(invocation.getMethod().getName());
      }
      return value;
    };
  }

  private int rows(String callId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM matrix_call_observation WHERE source_room_id = ? AND call_id = ?",
        Integer.class,
        SOURCE,
        callId);
  }

  private static User user(String id, String matrixId) {
    return User.builder()
        .userId(id)
        .username(id)
        .email(id + "@example.invalid")
        .tenantId(7L)
        .matrixUserId(matrixId)
        .build();
  }
}
