package de.caritas.cob.userservice.api.port.out;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent;
import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent.OutboxStatus;
import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.model.HandshakeSession.HandshakeStatus;
import de.caritas.cob.userservice.api.model.SupportAccessSession;
import de.caritas.cob.userservice.api.model.SupportAccessSession.SupportAccessSessionStatus;
import de.caritas.cob.userservice.api.service.handshake.HandshakePurpose;
import de.caritas.cob.userservice.api.service.handshake.SupportAccessJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class HandshakeRepositoriesIT {

  @Autowired private HandshakeOutboxEventRepository outboxRepository;
  @Autowired private SupportAccessSessionRepository sessionRepository;
  @Autowired private HandshakeSessionRepository handshakeRepository;

  @Test
  void claimShouldOnlyMoveOnePendingEventToProcessingOnce() {
    var now = nowInUtc();
    var event =
        outboxRepository.saveAndFlush(
            HandshakeOutboxEvent.builder()
                .aggregateId("hs-claim")
                .eventType(SupportAccessJob.PROVISION_ROOM.name())
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .createDate(now)
                .nextAttemptDate(now)
                .build());

    assertThat(outboxRepository.claim(event.getId())).isEqualTo(1);
    assertThat(outboxRepository.claim(event.getId())).isZero();
    assertThat(outboxRepository.findById(event.getId()).orElseThrow().getStatus())
        .isEqualTo(OutboxStatus.PROCESSING);
  }

  @Test
  void confirmIfStillPendingShouldSucceedExactlyOnce() {
    var handshake = handshakeRepository.saveAndFlush(pending("hs-confirm"));

    assertThat(handshakeRepository.confirmIfStillPending(handshake.getId(), nowInUtc()))
        .isEqualTo(1);
    // The second caller sees zero affected rows and must not create a session or a job.
    assertThat(handshakeRepository.confirmIfStillPending(handshake.getId(), nowInUtc())).isZero();
    assertThat(handshakeRepository.findById(handshake.getId()).orElseThrow().getStatus())
        .isEqualTo(HandshakeStatus.CONFIRMED);
  }

  @Test
  void confirmIfStillPendingShouldRefuseAnExpiredWindow() {
    var handshake = handshakeRepository.saveAndFlush(pending("hs-expired"));
    handshake.setExpiryDate(nowInUtc().minusMinutes(1));
    handshakeRepository.saveAndFlush(handshake);

    assertThat(handshakeRepository.confirmIfStillPending(handshake.getId(), nowInUtc())).isZero();
  }

  @Test
  void supportSessionShouldBeUniquePerHandshake() {
    sessionRepository.saveAndFlush(session("sess-1", "hs-unique", "lease-a"));

    assertThatThrownBy(
            () -> sessionRepository.saveAndFlush(session("sess-2", "hs-unique", "lease-b")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void onlyOneRunningLeaseIsAllowedPerSupportAdminConsultantAndAgency() {
    var lease = SupportAccessSession.leaseKeyOf("gsa-1", "consultant-1", 7L);
    sessionRepository.saveAndFlush(session("sess-a", "hs-a", lease));

    assertThatThrownBy(() -> sessionRepository.saveAndFlush(session("sess-b", "hs-b", lease)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void closedSessionsReleaseTheLeaseSoTheSamePairCanBeHelpedAgain() {
    var lease = SupportAccessSession.leaseKeyOf("gsa-2", "consultant-2", 8L);
    var first = sessionRepository.saveAndFlush(session("sess-c", "hs-c", lease));
    first.setStatus(SupportAccessSessionStatus.CLOSED);
    first.setActiveLeaseKey(null);
    sessionRepository.saveAndFlush(first);

    assertThatCode(() -> sessionRepository.saveAndFlush(session("sess-d", "hs-d", lease)))
        .doesNotThrowAnyException();
  }

  private HandshakeSession pending(String id) {
    var now = nowInUtc();
    return HandshakeSession.builder()
        .id(id)
        .purpose(HandshakePurpose.SUPPORT_ACCESS)
        .initiatorId("gsa-1")
        .counterpartId("consultant-1")
        .agencyId(7L)
        .tenantId(1L)
        .status(HandshakeStatus.PENDING)
        .createDate(now)
        .expiryDate(now.plusMinutes(5))
        .build();
  }

  private SupportAccessSession session(String id, String handshakeId, String leaseKey) {
    var now = nowInUtc();
    return SupportAccessSession.builder()
        .id(id)
        .handshakeId(handshakeId)
        .supportAdminId("gsa-1")
        .consultantId("consultant-1")
        .agencyId(7L)
        .status(SupportAccessSessionStatus.PROVISIONING)
        .activeLeaseKey(leaseKey)
        .createDate(now)
        .expiryDate(now.plusHours(4))
        .tenantId(1L)
        .build();
  }
}
