package de.caritas.cob.userservice.api.service.support;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus.ACTIVE;
import static de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus.CLOSED;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.model.SupportRoom;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SupportRoomRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
@Import(SupportRoomLifecycle.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SupportRoomClosureMariaDbIT {

  private static final String ADMIN_MATRIX_ID = "@support-replica:matrix.test";
  private static final String MATRIX_TOKEN = "synthetic-support-token";

  private final String roomId = UUID.randomUUID().toString();
  private final String handshakeId = UUID.randomUUID().toString();
  private final String matrixRoomId = "!" + roomId + ":matrix.test";

  @Autowired private SupportRoomRepository rooms;
  @Autowired private SupportRoomLifecycle lifecycle;
  @Autowired private AdminRepository admins;
  @Autowired private ConsultantRepository consultants;
  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add("spring.datasource.password", () -> System.getenv("LIQUIBASE_IT_DB_PASSWORD"));
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
  }

  @AfterEach
  void removeProofRows() {
    jdbc.update("DELETE FROM handshake_audit_event WHERE handshake_id = ?", handshakeId);
    rooms.deleteById(roomId);
  }

  @Test
  void twoSweepsThatReadTheSameExpiredRoomCloseItOnce() throws Exception {
    var now = nowInUtc();
    rooms.save(
        SupportRoom.builder()
            .id(roomId)
            .handshakeId(handshakeId)
            .matrixRoomId(matrixRoomId)
            .supportAdminId("support-replica-admin")
            .supportAdminMatrixId(ADMIN_MATRIX_ID)
            .consultantId("support-replica-consultant")
            .status(ACTIVE)
            .createDate(now.minusHours(5))
            .expiryDate(now.minusHours(1))
            .tenantId(1L)
            .build());

    var bothReadTheDueRoom = new CountDownLatch(2);
    var pausedReads = mock(SupportRoomRepository.class, delegatesTo(rooms));
    doAnswer(
            invocation -> {
              var due =
                  rooms.findClosureDue(
                      invocation.getArgument(0),
                      invocation.getArgument(1),
                      invocation.getArgument(2),
                      invocation.getArgument(3));
              assertThat(due).contains(roomId);
              bothReadTheDueRoom.countDown();
              assertThat(bothReadTheDueRoom.await(10, SECONDS))
                  .as("both due queries finished")
                  .isTrue();
              return due;
            })
        .when(pausedReads)
        .findClosureDue(anyList(), any(), any(), any());

    var matrix = mock(MatrixSynapseService.class);
    var leaveStarted = new CountDownLatch(1);
    var releaseLeave = new CountDownLatch(1);
    when(matrix.loginAsUserAccessToken(ADMIN_MATRIX_ID)).thenReturn(MATRIX_TOKEN);
    when(matrix.leaveRoom(matrixRoomId, MATRIX_TOKEN))
        .thenAnswer(
            ignored -> {
              leaveStarted.countDown();
              assertThat(releaseLeave.await(20, SECONDS)).as("Matrix leave released").isTrue();
              return true;
            });
    when(matrix.isOutOfRoom(matrixRoomId, ADMIN_MATRIX_ID)).thenReturn(Optional.of(true));

    var first = newSweep(pausedReads, matrix);
    var second = newSweep(pausedReads, matrix);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var sweeps = new ExecutorCompletionService<Void>(workers);
      sweeps.submit(first::sweepExpired, null);
      sweeps.submit(second::sweepExpired, null);
      try {
        assertThat(leaveStarted.await(10, SECONDS)).as("one sweep reached Matrix").isTrue();
        var contender = sweeps.poll(10, SECONDS);
        assertThat(contender).as("the other sweep finished while Matrix was paused").isNotNull();
        contender.get();
      } finally {
        releaseLeave.countDown();
      }
      var closer = sweeps.poll(10, SECONDS);
      assertThat(closer)
          .as("the closing sweep finished after Matrix confirmed departure")
          .isNotNull();
      closer.get();
    }

    verify(matrix).leaveRoom(matrixRoomId, MATRIX_TOKEN);
    verify(matrix).isOutOfRoom(matrixRoomId, ADMIN_MATRIX_ID);
    var closed = rooms.findById(roomId).orElseThrow();
    assertThat(closed.getStatus()).isEqualTo(CLOSED);
    assertThat(closed.getCloseReason()).isEqualTo("EXPIRED");
    assertThat(closed.getClosedDate()).isNotNull();
    assertThat(closed.getRetryAt()).isNull();
    assertThat(
            jdbc.queryForList(
                "SELECT event FROM handshake_audit_event WHERE handshake_id = ?",
                String.class,
                handshakeId))
        .containsExactly("SUPPORT_ROOM_CLOSED");
  }

  private SupportRoomService newSweep(SupportRoomRepository dueRooms, MatrixSynapseService matrix) {
    var service =
        new SupportRoomService(
            dueRooms, lifecycle, admins, consultants, matrix, mock(MatrixConfig.class));
    ReflectionTestUtils.setField(service, "sweepBatchSize", 20);
    return service;
  }
}
