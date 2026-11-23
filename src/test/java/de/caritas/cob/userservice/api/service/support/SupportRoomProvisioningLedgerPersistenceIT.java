package de.caritas.cob.userservice.api.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.model.HandshakeSession.HandshakeStatus;
import de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus;
import de.caritas.cob.userservice.api.model.SupportRoomCreationIntent;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeSessionRepository;
import de.caritas.cob.userservice.api.port.out.SupportRoomCreationIntentRepository;
import de.caritas.cob.userservice.api.port.out.SupportRoomRepository;
import de.caritas.cob.userservice.api.service.handshake.HandshakePurpose;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.profiles.active=testing")
@Import({SupportRoomProvisioningLedger.class, SupportRoomLifecycle.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SupportRoomProvisioningLedgerPersistenceIT {
  private static final Instant START =
      Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
  private static final LocalDateTime DEADLINE =
      LocalDateTime.ofInstant(START.plusSeconds(180), ZoneOffset.UTC);
  private static final String SUPPORT_ADMIN = "synthetic-support";
  private static final String SUPPORT_MATRIX = "@synthetic-support:example.org";
  private static final String CONSULTANT = "synthetic-consultant";
  private static final String CONSULTANT_MATRIX = "@synthetic-consultant:example.org";
  private static final String ROOM = "!synthetic-support:example.org";
  private static final String OTHER_ROOM = "!synthetic-other:example.org";

  @Autowired private SupportRoomProvisioningLedger ledger;
  @Autowired private SupportRoomLifecycle lifecycle;
  @Autowired private HandshakeSessionRepository handshakes;
  @Autowired private SupportRoomCreationIntentRepository intents;
  @Autowired private SupportRoomRepository rooms;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;
  @MockitoBean private Clock clock;

  private final String handshakeId = UUID.randomUUID().toString();
  private String roomId;

  @DynamicPropertySource
  static void configureMariaDbWhenProvided(DynamicPropertyRegistry registry) {
    var url = System.getenv("LIQUIBASE_IT_DB_URL");
    if (url == null || url.isBlank()) {
      return;
    }
    registry.add("spring.datasource.url", () -> url);
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
    registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
    registry.add(
        "spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MariaDBDialect");
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
    registry.add("spring.sql.init.mode", () -> "never");
  }

  @BeforeEach
  void fixClock() {
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    when(clock.instant()).thenReturn(START);
  }

  @AfterEach
  void removeFixtures() {
    intents.deleteById(handshakeId);
    handshakes.deleteById(handshakeId);
    if (roomId != null) {
      rooms.deleteById(roomId);
    }
  }

  @Test
  void roomCreationIntentSurvivesRollbackAndPreservesItsExactScopeOnRetry() {
    var transactions = new TransactionTemplate(transactionManager);
    var first =
        transactions.execute(
            state -> {
              var reservation = reserve();
              state.setRollbackOnly();
              return reservation;
            });
    roomId = first.intent().getId();

    var retry = reserve();

    assertThat(first.firstAttempt()).isTrue();
    assertThat(retry.firstAttempt()).isFalse();
    assertThat(retry.intent().getId()).isEqualTo(roomId).isNotEqualTo(handshakeId);
    assertThat(retry.intent().getRoomAlias()).isEqualTo("support-" + roomId);
    assertThat(retry.intent().getNonce()).isNotBlank().isEqualTo(first.intent().getNonce());
    assertThat(rooms.findById(roomId)).isEmpty();
    assertThatThrownBy(
            () ->
                ledger.reserve(
                    handshakeId,
                    SUPPORT_ADMIN,
                    SUPPORT_MATRIX,
                    CONSULTANT,
                    "@different-consultant:example.org",
                    1L,
                    DEADLINE))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void confirmedBindingIsIdempotentAndRollbackLeavesProvisioningForCleanup() {
    var created = reserve().intent();
    roomId = created.getId();
    ledger.bindForConfirmation(created, ROOM);
    ledger.bindForConfirmation(created, ROOM);
    assertThatThrownBy(() -> ledger.bindForConfirmation(created, OTHER_ROOM))
        .isInstanceOf(IllegalStateException.class);

    var transactions = new TransactionTemplate(transactionManager);
    transactions.executeWithoutResult(
        state -> {
          lifecycle.activate(roomId, 4);
          ledger.forgetConfirmed(handshakeId);
          state.setRollbackOnly();
        });

    var provisional = rooms.findById(roomId).orElseThrow();
    assertThat(provisional.getStatus()).isEqualTo(SupportRoomStatus.PROVISIONING);
    assertThat(provisional.getMatrixRoomId()).isEqualTo(ROOM);
    assertThat(intents.findById(handshakeId)).isPresent();

    when(clock.instant()).thenReturn(START.plusSeconds(181));
    assertThat(ledger.expiredIds(LocalDateTime.now(clock), 5)).contains(handshakeId);
    assertThat(ledger.claimExpired(handshakeId)).isEmpty();
    assertThat(intents.findById(handshakeId)).isEmpty();
    assertThat(rooms.findById(roomId)).isPresent();
  }

  @Test
  void concurrentConfirmationCanOnlyReserveOneCreationAttempt() throws Exception {
    handshakes.save(
        HandshakeSession.builder()
            .id(handshakeId)
            .purpose(HandshakePurpose.SUPPORT_ACCESS)
            .initiatorId(SUPPORT_ADMIN)
            .counterpartId(CONSULTANT)
            .status(HandshakeStatus.PENDING)
            .createDate(LocalDateTime.now(clock))
            .expiryDate(DEADLINE)
            .tenantId(1L)
            .build());
    var transactions = new TransactionTemplate(transactionManager);
    var firstReserved = new CountDownLatch(1);
    var allowRollback = new CountDownLatch(1);
    var secondStarted = new CountDownLatch(1);

    try (var workers = Executors.newFixedThreadPool(2)) {
      try {
        var first =
            workers.submit(
                () ->
                    transactions.execute(
                        state -> {
                          handshakes.findByIdForUpdate(handshakeId).orElseThrow();
                          var reservation = reserve();
                          firstReserved.countDown();
                          await(allowRollback);
                          state.setRollbackOnly();
                          return reservation;
                        }));
        await(firstReserved);

        var second =
            workers.submit(
                () ->
                    transactions.execute(
                        state -> {
                          secondStarted.countDown();
                          handshakes.findByIdForUpdate(handshakeId).orElseThrow();
                          return reserve();
                        }));
        await(secondStarted);
        assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        allowRollback.countDown();

        var original = first.get(5, TimeUnit.SECONDS);
        var retry = second.get(5, TimeUnit.SECONDS);
        assertThat(original.firstAttempt()).isTrue();
        assertThat(retry.firstAttempt()).isFalse();
        assertThat(retry.intent().getId()).isEqualTo(original.intent().getId());
      } finally {
        allowRollback.countDown();
      }
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
  void cleanupClaimSkipsAHandshakeLockedByAnOngoingConfirmation() throws Exception {
    handshakes.save(
        HandshakeSession.builder()
            .id(handshakeId)
            .purpose(HandshakePurpose.SUPPORT_ACCESS)
            .initiatorId(SUPPORT_ADMIN)
            .counterpartId(CONSULTANT)
            .status(HandshakeStatus.PENDING)
            .createDate(LocalDateTime.now(clock))
            .expiryDate(DEADLINE)
            .tenantId(1L)
            .build());
    var attempt = reserve().intent();
    roomId = attempt.getId();
    assertThat(ledger.bindForConfirmation(attempt, ROOM)).isTrue();
    when(clock.instant()).thenReturn(DEADLINE.plusSeconds(1).toInstant(ZoneOffset.UTC));
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var tx = new TransactionTemplate(transactionManager);
    tx.setTimeout(8);
    var workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
    var confirmation =
        workers.submit(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      handshakes.findByIdForUpdate(handshakeId).orElseThrow();
                      locked.countDown();
                      await(release);
                    }));
    try {
      await(locked);
      var attemptedCleanup =
          workers.submit(
              () -> {
                try {
                  return lifecycle.claimClosure(roomId).isEmpty();
                } catch (PessimisticLockingFailureException conflict) {
                  return true;
                }
              });
      assertThat(attemptedCleanup.get(3, TimeUnit.SECONDS)).isTrue();
      assertThat(rooms.findById(roomId).orElseThrow().getStatus())
          .isEqualTo(SupportRoomStatus.PROVISIONING);
      release.countDown();
      confirmation.get(5, TimeUnit.SECONDS);
      assertThat(lifecycle.claimClosure(roomId)).isPresent();
    } finally {
      release.countDown();
      workers.shutdownNow();
      assertThat(workers.awaitTermination(9, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AssertionError(ex);
    }
  }

  @Test
  void retryCannotExtendTheOriginalProvisioningGrace() {
    var handshakeDeadline = DEADLINE.plusMinutes(2);
    var created =
        ledger
            .reserve(
                handshakeId,
                SUPPORT_ADMIN,
                SUPPORT_MATRIX,
                CONSULTANT,
                CONSULTANT_MATRIX,
                1L,
                handshakeDeadline)
            .intent();
    roomId = created.getId();
    ledger.bindForConfirmation(created, ROOM);

    when(clock.instant()).thenReturn(START.plusSeconds(120));
    ledger.bindForConfirmation(created, ROOM);
    assertThat(rooms.findById(roomId).orElseThrow().getExpiryDate()).isEqualTo(DEADLINE);

    when(clock.instant()).thenReturn(START.plusSeconds(181));
    assertThat(ledger.bindForConfirmation(created, ROOM)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {120, 181})
  void delayedFirstBindingKeepsTheDeadlineFromTheOriginalReservation(int secondsAfterReservation) {
    var handshakeDeadline = DEADLINE.plusMinutes(2);
    var created =
        ledger
            .reserve(
                handshakeId,
                SUPPORT_ADMIN,
                SUPPORT_MATRIX,
                CONSULTANT,
                CONSULTANT_MATRIX,
                1L,
                handshakeDeadline)
            .intent();
    roomId = created.getId();

    when(clock.instant()).thenReturn(START.plusSeconds(secondsAfterReservation));
    assertThat(ledger.bindForConfirmation(created, ROOM)).isEqualTo(secondsAfterReservation < 180);

    var room = rooms.findById(roomId).orElseThrow();
    assertThat(room.getStatus()).isEqualTo(SupportRoomStatus.PROVISIONING);
    assertThat(room.getExpiryDate()).isEqualTo(DEADLINE);
  }

  @Test
  void roomVerifiedAfterHandshakeDeadlineIsPersistedForWithdrawal() {
    var created = reserve().intent();
    roomId = created.getId();
    when(clock.instant()).thenReturn(DEADLINE.plusSeconds(1).toInstant(ZoneOffset.UTC));

    assertThat(ledger.bindForConfirmation(created, ROOM)).isFalse();

    var room = rooms.findById(roomId).orElseThrow();
    assertThat(room.getStatus()).isEqualTo(SupportRoomStatus.PROVISIONING);
    assertThat(room.getMatrixRoomId()).isEqualTo(ROOM);
    assertThat(room.getExpiryDate()).isEqualTo(DEADLINE);
  }

  @Test
  void legacyUnboundIntentKeepsARecoveredRoomOnlyForWithdrawal() {
    var created = reserve().intent();
    roomId = created.getId();
    jdbc.update(
        "UPDATE support_room_creation_intent SET provisioning_deadline = NULL WHERE handshake_id = ?",
        handshakeId);

    assertThat(ledger.bindForConfirmation(created, ROOM)).isFalse();

    var room = rooms.findById(roomId).orElseThrow();
    assertThat(room.getStatus()).isEqualTo(SupportRoomStatus.PROVISIONING);
    assertThat(room.getExpiryDate()).isEqualTo(LocalDateTime.now(clock));
  }

  @Test
  void claimKeepsTheFullLeaseAfterTheClockAdvances() {
    roomId = reserve().intent().getId();
    var requestedAt = DEADLINE.plusSeconds(1);
    var lockAcquiredAt = requestedAt.plusSeconds(25);
    when(clock.instant()).thenReturn(lockAcquiredAt.toInstant(ZoneOffset.UTC));

    var claim = ledger.claimExpired(handshakeId).orElseThrow();

    assertThat(claim.getRetryAt()).isEqualTo(lockAcquiredAt.plusMinutes(1));
  }

  @Test
  void unresolvedSweepSchedulesTheNextProbeBeyondTheOriginalOneMinuteClaim() {
    var sweepAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    var expiredAt = LocalDateTime.ofInstant(sweepAt.minusSeconds(60), ZoneOffset.UTC);
    when(clock.instant()).thenReturn(sweepAt.minusSeconds(240));
    var attempt =
        ledger
            .reserve(
                handshakeId,
                SUPPORT_ADMIN,
                SUPPORT_MATRIX,
                CONSULTANT,
                CONSULTANT_MATRIX,
                1L,
                expiredAt)
            .intent();
    roomId = attempt.getId();
    var matrix = mock(MatrixSynapseService.class);
    var matrixConfig = mock(MatrixConfig.class);
    when(matrixConfig.getServerName()).thenReturn("example.org");
    when(matrix.resolveRoomAlias("#" + attempt.getRoomAlias() + ":example.org"))
        .thenReturn(new MatrixSynapseService.RoomAliasLookup(true, null));
    var service =
        new SupportRoomService(
            rooms,
            lifecycle,
            ledger,
            mock(AdminRepository.class),
            mock(ConsultantRepository.class),
            matrix,
            matrixConfig);
    ReflectionTestUtils.setField(service, "sweepBatchSize", 20);
    when(clock.instant()).thenReturn(sweepAt);

    service.sweepExpired();

    assertThat(intents.findById(handshakeId).orElseThrow().getRetryAt())
        .isEqualTo(LocalDateTime.ofInstant(sweepAt.plusSeconds(120), ZoneOffset.UTC));
    assertThat(rooms.findById(roomId)).isEmpty();
  }

  @Test
  void unresolvedClaimsPersistCappedBackoffWithoutExtendingTheirExclusiveLease() {
    roomId = reserve().intent().getId();
    var nextProbe = DEADLINE.plusSeconds(1);
    var expectedFailures = 0;
    SupportRoomCreationIntent precedingClaim = null;
    for (int delay : List.of(2, 4, 8, 16, 30, 30)) {
      when(clock.instant()).thenReturn(nextProbe.toInstant(ZoneOffset.UTC));
      var claim = ledger.claimExpired(handshakeId).orElseThrow();
      assertThat(claim.getRetryAt()).isEqualTo(nextProbe.plusMinutes(1));
      assertThat(ledger.claimExpired(handshakeId)).isEmpty();

      if (precedingClaim != null) {
        ledger.deferUnresolvedRecovery(precedingClaim);
        var unchanged = intents.findById(handshakeId).orElseThrow();
        assertThat(unchanged.getRetryAt()).isEqualTo(claim.getRetryAt());
        assertThat(unchanged.getRecoveryProbeFailures()).isEqualTo(expectedFailures);
      }

      var resultAt = nextProbe.plusSeconds(15);
      when(clock.instant()).thenReturn(resultAt.toInstant(ZoneOffset.UTC));
      ledger.deferUnresolvedRecovery(claim);
      ledger.deferUnresolvedRecovery(claim);
      nextProbe = resultAt.plusMinutes(delay);
      expectedFailures = Math.min(expectedFailures + 1, 5);
      var persisted = intents.findById(handshakeId).orElseThrow();
      assertThat(persisted.getRetryAt()).isEqualTo(nextProbe);
      assertThat(persisted.getRecoveryProbeFailures()).isEqualTo(expectedFailures);
      assertThat(ledger.expiredIds(nextProbe.minusSeconds(1), 5)).doesNotContain(handshakeId);
      precedingClaim = claim;
    }

    when(clock.instant()).thenReturn(nextProbe.toInstant(ZoneOffset.UTC));
    var current = ledger.claimExpired(handshakeId).orElseThrow();
    ledger.bindExpiredForCleanup(current, ROOM);
    assertThat(rooms.findById(roomId)).isPresent();
    assertThat(intents.findById(handshakeId)).isEmpty();
  }

  @Test
  void expiredAttemptRetriesAndOnlyCurrentClaimCanTransferCleanup() {
    var initial = reserve().intent();
    roomId = initial.getId();

    when(clock.instant()).thenReturn(START.plusSeconds(181));
    var firstClaimAt = LocalDateTime.now(clock);
    var firstClaim = ledger.claimExpired(handshakeId).orElseThrow();
    assertThat(ledger.claimExpired(handshakeId)).isEmpty();
    assertThat(ledger.expiredIds(firstClaimAt, 5)).doesNotContain(handshakeId);
    assertThat(rooms.findById(roomId)).isEmpty();
    assertThatThrownBy(() -> ledger.bindForConfirmation(initial, ROOM))
        .isInstanceOf(IllegalStateException.class);

    when(clock.instant()).thenReturn(START.plusSeconds(243));
    var secondClaim = ledger.claimExpired(handshakeId).orElseThrow();
    assertThatThrownBy(() -> ledger.bindExpiredForCleanup(firstClaim, OTHER_ROOM))
        .isInstanceOf(IllegalStateException.class);
    ledger.bindExpiredForCleanup(secondClaim, ROOM);

    var cleanup = rooms.findById(roomId).orElseThrow();
    assertThat(cleanup.getStatus()).isEqualTo(SupportRoomStatus.PROVISIONING);
    assertThat(cleanup.getMatrixRoomId()).isEqualTo(ROOM);
    assertThat(cleanup.getExpiryDate()).isEqualTo(DEADLINE);
    assertThat(intents.findById(handshakeId)).isEmpty();
  }

  private SupportRoomProvisioningLedger.Reservation reserve() {
    return ledger.reserve(
        handshakeId, SUPPORT_ADMIN, SUPPORT_MATRIX, CONSULTANT, CONSULTANT_MATRIX, 1L, DEADLINE);
  }
}
