package de.caritas.cob.userservice.api.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.model.HandshakeSession.HandshakeStatus;
import de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus;
import de.caritas.cob.userservice.api.port.out.HandshakeSessionRepository;
import de.caritas.cob.userservice.api.port.out.SupportRoomCreationIntentRepository;
import de.caritas.cob.userservice.api.port.out.SupportRoomRepository;
import de.caritas.cob.userservice.api.service.handshake.HandshakePurpose;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
  @MockitoBean private Clock clock;

  private final String handshakeId = UUID.randomUUID().toString();
  private String roomId;

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
    var afterDeadline = LocalDateTime.now(clock);
    assertThat(ledger.expiredIds(afterDeadline, 5)).contains(handshakeId);
    assertThat(ledger.claimExpired(handshakeId, afterDeadline)).isEmpty();
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
    assertThatThrownBy(() -> ledger.bindForConfirmation(created, ROOM))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Support room provisioning grace has expired");
  }

  @Test
  void expiredAttemptRetriesAndOnlyCurrentClaimCanTransferCleanup() {
    var initial = reserve().intent();
    roomId = initial.getId();

    when(clock.instant()).thenReturn(START.plusSeconds(181));
    var firstClaimAt = LocalDateTime.now(clock);
    var firstClaim = ledger.claimExpired(handshakeId, firstClaimAt).orElseThrow();
    assertThat(ledger.claimExpired(handshakeId, firstClaimAt)).isEmpty();
    assertThat(ledger.expiredIds(firstClaimAt, 5)).doesNotContain(handshakeId);
    assertThat(rooms.findById(roomId)).isEmpty();
    assertThatThrownBy(() -> ledger.bindForConfirmation(initial, ROOM))
        .isInstanceOf(IllegalStateException.class);

    when(clock.instant()).thenReturn(START.plusSeconds(243));
    var secondClaim = ledger.claimExpired(handshakeId, LocalDateTime.now(clock)).orElseThrow();
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
