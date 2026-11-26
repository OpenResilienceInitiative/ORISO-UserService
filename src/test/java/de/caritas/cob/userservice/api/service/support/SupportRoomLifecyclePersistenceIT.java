package de.caritas.cob.userservice.api.service.support;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus.ACTIVE;
import static de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus.CLOSED;
import static de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus.PROVISIONING;
import static de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus.REVOCATION_PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.model.SupportRoom;
import de.caritas.cob.userservice.api.port.out.HandshakeSessionRepository;
import de.caritas.cob.userservice.api.port.out.SupportRoomRepository;
import de.caritas.cob.userservice.api.service.handshake.HandshakePurpose;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.profiles.active=testing")
@Import(SupportRoomLifecycle.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SupportRoomLifecyclePersistenceIT {
  @Autowired private SupportRoomLifecycle lifecycle;
  @Autowired private SupportRoomRepository rooms;
  @Autowired private HandshakeSessionRepository handshakes;
  @Autowired private PlatformTransactionManager transactionManager;
  @MockitoBean private Clock clock;

  @Test
  void provisionalCleanupSurvivesRollbackEvenAfterActivationWasPrepared() {
    var now = nowInUtc();
    var room = provisionalRoom(now);
    var transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(status -> rooms.save(room));
    transaction.executeWithoutResult(
        status -> {
          assertThat(lifecycle.activate(room.getId(), 4).getStatus()).isEqualTo(ACTIVE);
          status.setRollbackOnly();
        });

    var afterRollback = rooms.findById(room.getId()).orElseThrow();
    assertThat(afterRollback.getStatus()).isEqualTo(PROVISIONING);
    assertThat(afterRollback.getMatrixRoomId()).isEqualTo(room.getMatrixRoomId());
    assertThat(afterRollback.getSupportAdminMatrixId()).isEqualTo(room.getSupportAdminMatrixId());

    var later = now.plusMinutes(4);
    assertThat(
            rooms.findClosureDue(
                List.of(ACTIVE, PROVISIONING), REVOCATION_PENDING, later, PageRequest.of(0, 20)))
        .contains(room.getId());
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    when(clock.instant()).thenReturn(later.toInstant(ZoneOffset.UTC));
    assertThat(lifecycle.claimClosure(room.getId())).isPresent();
    var pending = rooms.findById(room.getId()).orElseThrow();
    assertThat(pending.getStatus()).isEqualTo(REVOCATION_PENDING);
    assertThat(pending.getCloseReason()).isEqualTo("PROVISION_FAILED");
    assertThat(pending.getClosedDate()).isNull();
    assertThat(lifecycle.claimClosure(room.getId())).isEmpty();
    when(clock.instant()).thenReturn(later.plusMinutes(2).toInstant(ZoneOffset.UTC));
    assertThat(lifecycle.claimClosure(room.getId())).isPresent();

    lifecycle.confirmClosure(room.getId());

    assertThat(rooms.findById(room.getId()).orElseThrow().getStatus()).isEqualTo(CLOSED);
    assertThat(lifecycle.claimClosure(room.getId())).isEmpty();
    rooms.deleteById(room.getId());
  }

  /** The sweep must not start withdrawing a room whose confirmation still holds its handshake. */
  @Test
  void closureClaimDefersWhileAConfirmationHoldsTheHandshake() throws Exception {
    var now = nowInUtc();
    var handshake = handshakeFor(UUID.randomUUID().toString(), now);
    var room = provisionalRoom(now);
    room.setHandshakeId(handshake.getId());
    var transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(
        status -> {
          handshakes.save(handshake);
          rooms.save(room);
        });
    var later = now.plusMinutes(4);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    when(clock.instant()).thenReturn(later.toInstant(ZoneOffset.UTC));

    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var workers = Executors.newFixedThreadPool(2);
    try {
      var confirmation =
          workers.submit(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        assertThat(handshakes.findByIdForUpdate(handshake.getId())).isPresent();
                        locked.countDown();
                        await(release);
                      }));
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

      var sweep = workers.submit(() -> lifecycle.claimClosure(room.getId()));
      try {
        // Either the sweep fails fast on the held lock or it is still waiting; it never claims.
        assertThat(sweep.get(2, TimeUnit.SECONDS)).isEmpty();
      } catch (java.util.concurrent.ExecutionException deferred) {
        // The NOWAIT lock refused the claim; the next sweep retries.
      } catch (TimeoutException stillWaiting) {
        // Still blocked on the handshake lock.
      }
      assertThat(rooms.findById(room.getId()).orElseThrow().getStatus()).isEqualTo(PROVISIONING);

      release.countDown();
      confirmation.get(10, TimeUnit.SECONDS);
      try {
        sweep.get(10, TimeUnit.SECONDS);
      } catch (java.util.concurrent.ExecutionException deferred) {
        // Already reported above.
      }
    } finally {
      release.countDown();
      workers.shutdownNow();
      assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    // Once the confirmation has committed, the room is withdrawn by this or the next sweep.
    if (rooms.findById(room.getId()).orElseThrow().getStatus() == PROVISIONING) {
      assertThat(lifecycle.claimClosure(room.getId())).isPresent();
    }
    assertThat(rooms.findById(room.getId()).orElseThrow().getStatus())
        .isEqualTo(REVOCATION_PENDING);

    rooms.deleteById(room.getId());
    handshakes.deleteById(handshake.getId());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Test latch was not released");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private static HandshakeSession handshakeFor(String id, LocalDateTime now) {
    return HandshakeSession.builder()
        .id(id)
        .purpose(HandshakePurpose.SUPPORT_ACCESS)
        .initiatorId("support-test-admin")
        .counterpartId("support-test-consultant")
        .status(HandshakeSession.HandshakeStatus.PENDING)
        .createDate(now)
        .expiryDate(now.plusMinutes(5))
        .tenantId(1L)
        .build();
  }

  private SupportRoom provisionalRoom(LocalDateTime now) {
    return SupportRoom.builder()
        .id(UUID.randomUUID().toString())
        .handshakeId("support-handshake")
        .matrixRoomId("!support-test:example.org")
        .supportAdminId("support-test-admin")
        .supportAdminMatrixId("@support-test-admin:example.org")
        .consultantId("support-test-consultant")
        .status(PROVISIONING)
        .createDate(now)
        .expiryDate(now.plusMinutes(3))
        .tenantId(1L)
        .build();
  }
}
