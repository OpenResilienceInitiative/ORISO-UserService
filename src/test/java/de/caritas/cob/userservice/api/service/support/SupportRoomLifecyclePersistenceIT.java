package de.caritas.cob.userservice.api.service.support;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus.ACTIVE;
import static de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus.CLOSED;
import static de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus.PROVISIONING;
import static de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus.REVOCATION_PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.SupportRoom;
import de.caritas.cob.userservice.api.port.out.SupportRoomRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
