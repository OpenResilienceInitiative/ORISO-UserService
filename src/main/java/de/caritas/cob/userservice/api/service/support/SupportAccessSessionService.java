package de.caritas.cob.userservice.api.service.support;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent;
import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent.OutboxStatus;
import de.caritas.cob.userservice.api.model.SupportAccessSession;
import de.caritas.cob.userservice.api.model.SupportAccessSession.SupportAccessSessionStatus;
import de.caritas.cob.userservice.api.port.out.HandshakeOutboxEventRepository;
import de.caritas.cob.userservice.api.port.out.SupportAccessRevoker;
import de.caritas.cob.userservice.api.port.out.SupportAccessSessionRepository;
import de.caritas.cob.userservice.api.service.handshake.SupportAccessJob;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read and command surface of a temporary support session (ADR-018 §4). Everything that touches
 * Matrix lives in {@link SupportAccessMatrixWorker}; this service owns only database state, so the
 * two race-critical transitions — starting a session and starting its withdrawal — stay short,
 * conditional, and free of external calls.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SupportAccessSessionService implements SupportAccessRevoker {

  public static final String REASON_EXPIRED = "EXPIRED";
  public static final String REASON_TERMINATED = "TERMINATED";

  private final @NonNull SupportAccessSessionRepository sessionRepository;
  private final @NonNull HandshakeOutboxEventRepository outboxRepository;

  /**
   * Only the two participants may see a session, and only while it is genuinely usable. From
   * REVOCATION_PENDING onwards nothing is reported as active any more, even though the Matrix
   * removal may still be in flight — the API must never be ahead of the security state.
   */
  @Transactional(readOnly = true)
  public List<SupportAccessSessionItem> activeFor(AuthenticatedUser caller) {
    var usable =
        List.of(SupportAccessSessionStatus.PROVISIONING, SupportAccessSessionStatus.ACTIVE);
    var asConsultant =
        sessionRepository.findAllByStatusInAndConsultantId(usable, caller.getUserId());
    var asSupportAdmin =
        sessionRepository.findAllByStatusInAndSupportAdminId(usable, caller.getUserId());
    return java.util.stream.Stream.concat(asConsultant.stream(), asSupportAdmin.stream())
        .map(SupportAccessSessionItem::of)
        .toList();
  }

  /** The Berater*in ends the session early — same hard withdrawal as the four-hour cutoff. */
  @Transactional
  public void terminate(AuthenticatedUser caller, String sessionId) {
    var session = requireSession(sessionId);
    if (!session.getConsultantId().equals(caller.getUserId())) {
      throw new ForbiddenException(
          String.format(
              "User %s may not terminate support session %s", caller.getUserId(), sessionId));
    }
    if (session.getStatus().isTerminal()
        || session.getStatus() == SupportAccessSessionStatus.REVOCATION_PENDING) {
      throw new ConflictException(String.format("Support session %s is already ending", sessionId));
    }
    beginRevocation(session.getId(), REASON_TERMINATED);
  }

  /**
   * Element Call reports its media room. Registering it late — after the session already started
   * ending — still has to purge that room, otherwise a call outlives the lease.
   */
  @Transactional
  public void registerCallRoom(AuthenticatedUser caller, String sessionId, String callRoomId) {
    var session = requireSession(sessionId);
    if (!session.getConsultantId().equals(caller.getUserId())
        && !session.getSupportAdminId().equals(caller.getUserId())) {
      throw new ForbiddenException(
          String.format(
              "User %s is not a participant of support session %s", caller.getUserId(), sessionId));
    }
    if (session.getCallMatrixRoomId() != null
        && !session.getCallMatrixRoomId().equals(callRoomId)) {
      // A second media room would survive revocation unnoticed.
      throw new ConflictException(
          String.format("Support session %s already has a call room", sessionId));
    }
    session.setCallMatrixRoomId(callRoomId);
    sessionRepository.saveAndFlush(session);

    if (session.getStatus().isTerminal()
        || session.getStatus() == SupportAccessSessionStatus.REVOCATION_PENDING) {
      enqueue(SupportAccessJob.PURGE_CALL_ROOM.name(), session.getId());
    }
  }

  /**
   * Conditional transition into REVOCATION_PENDING plus exactly one withdrawal job. Expiry, manual
   * termination, and disabling the support admin all race here; the loser sees zero affected rows
   * and does nothing, so the job is never enqueued twice.
   */
  @Transactional
  public boolean beginRevocation(String sessionId, String reason) {
    if (sessionRepository.beginRevocation(sessionId, reason, nowInUtc()) != 1) {
      return false;
    }
    enqueue(SupportAccessJob.REVOKE_ACCESS.name(), sessionId);
    return true;
  }

  @Override
  @Transactional
  public int revokeAllForSupportAdmin(String supportAdminId, String reason) {
    var affected =
        sessionRepository.findAllBySupportAdminIdAndStatusIn(
            supportAdminId,
            List.of(SupportAccessSessionStatus.PROVISIONING, SupportAccessSessionStatus.ACTIVE));
    var revoked = 0;
    for (var session : affected) {
      if (beginRevocation(session.getId(), reason)) {
        revoked++;
      }
    }
    return revoked;
  }

  private SupportAccessSession requireSession(String sessionId) {
    return sessionRepository
        .findById(sessionId)
        .orElseThrow(
            () -> new BadRequestException(String.format("Unknown support session %s", sessionId)));
  }

  /**
   * The unique {@code (aggregate_id, event_type)} constraint is what makes this idempotent: a job
   * that already exists is simply not created again.
   */
  private void enqueue(String jobType, String aggregateId) {
    if (outboxRepository.existsByAggregateIdAndEventType(aggregateId, jobType)) {
      return;
    }
    var now = nowInUtc();
    outboxRepository.save(
        HandshakeOutboxEvent.builder()
            .aggregateId(aggregateId)
            .eventType(jobType)
            .status(OutboxStatus.PENDING)
            .attempts(0)
            .createDate(now)
            .nextAttemptDate(now)
            .build());
  }

  @Getter
  public static class SupportAccessSessionItem {
    private String id;
    private String handshakeId;
    private String matrixRoomId;
    private String callMatrixRoomId;
    private String supportAdminId;
    private String supportAdminMatrixId;
    private String consultantId;
    private Long agencyId;
    private String status;
    private LocalDateTime expiryDate;

    public static SupportAccessSessionItem of(SupportAccessSession session) {
      var item = new SupportAccessSessionItem();
      item.id = session.getId();
      item.handshakeId = session.getHandshakeId();
      item.matrixRoomId = session.getMatrixRoomId();
      item.callMatrixRoomId = session.getCallMatrixRoomId();
      item.supportAdminId = session.getSupportAdminId();
      item.supportAdminMatrixId = session.getSupportAdminMatrixId();
      item.consultantId = session.getConsultantId();
      item.agencyId = session.getAgencyId();
      item.status = session.getStatus().name();
      item.expiryDate = session.getExpiryDate();
      return item;
    }
  }
}
