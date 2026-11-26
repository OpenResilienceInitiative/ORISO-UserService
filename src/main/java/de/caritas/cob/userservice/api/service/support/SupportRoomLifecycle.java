package de.caritas.cob.userservice.api.service.support;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus.ACTIVE;
import static de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus.CLOSED;
import static de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus.PROVISIONING;
import static de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus.REVOCATION_PENDING;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.HandshakeAuditEvent;
import de.caritas.cob.userservice.api.model.SupportRoom;
import de.caritas.cob.userservice.api.port.out.HandshakeAuditEventRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeSessionRepository;
import de.caritas.cob.userservice.api.port.out.SupportRoomRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits the room lifecycle separately from its Matrix side effects. */
@Service
@RequiredArgsConstructor
class SupportRoomLifecycle {

  private static final String REASON_EXPIRED = "EXPIRED";
  private static final String REASON_TERMINATED = "TERMINATED";
  private static final String REASON_PROVISION_FAILED = "PROVISION_FAILED";

  private final SupportRoomRepository rooms;
  private final HandshakeAuditEventRepository auditEvents;
  private final HandshakeSessionRepository handshakes;
  private final Clock clock;

  /** The verified activation and its audit still commit with the confirmation. */
  @Transactional(propagation = Propagation.MANDATORY)
  public SupportRoom activate(String roomId, long ttlHours) {
    var room = rooms.lockById(roomId).orElseThrow();
    var now = nowInUtc();
    if (room.getStatus() != PROVISIONING || !room.getExpiryDate().isAfter(now)) {
      throw new IllegalStateException("Support room provisioning is no longer current");
    }
    room.setStatus(ACTIVE);
    room.setExpiryDate(now.plusHours(ttlHours));
    audit(room, "SUPPORT_ROOM_CREATED", null);
    return room;
  }

  @Transactional
  public void terminate(String roomId, String callerId) {
    var room =
        rooms
            .lockById(roomId)
            .orElseThrow(() -> new BadRequestException("Unknown support room " + roomId));
    if (!room.getConsultantId().equals(callerId)) {
      throw new ForbiddenException("Only the assigned consultant may terminate a support room");
    }
    if (room.getStatus() != ACTIVE) {
      throw new BadRequestException("Support room is not active");
    }
    beginRevocation(room, REASON_TERMINATED, nowInUtc());
  }

  /** Reserves one due attempt and commits its next retry before any Matrix call. */
  @Transactional
  public Optional<Revocation> claimClosure(String roomId) {
    // A confirmation holds the handshake lock across the last Matrix step and activation.
    rooms
        .findHandshakeIdByRoomIdAndStatus(roomId, PROVISIONING)
        .ifPresent(handshakes::lockForSupportCleanup);
    var room = rooms.lockById(roomId).orElse(null);
    if (room == null || room.getStatus() == CLOSED) {
      return Optional.empty();
    }
    var now = LocalDateTime.now(clock);
    if (room.getStatus() == REVOCATION_PENDING) {
      if (room.getRetryAt().isAfter(now)) {
        return Optional.empty();
      }
    } else {
      if (room.getExpiryDate().isAfter(now)) {
        return Optional.empty();
      }
      var reason = room.getStatus() == PROVISIONING ? REASON_PROVISION_FAILED : REASON_EXPIRED;
      beginRevocation(room, reason, now);
    }
    room.setRetryAt(now.plusMinutes(1));
    return Optional.of(
        new Revocation(room.getId(), room.getMatrixRoomId(), room.getSupportAdminMatrixId()));
  }

  @Transactional
  public void confirmClosure(String roomId) {
    var room = rooms.lockById(roomId).orElseThrow();
    if (room.getStatus() != REVOCATION_PENDING) {
      return;
    }
    room.setStatus(CLOSED);
    room.setClosedDate(nowInUtc());
    room.setRetryAt(null);
    var actorId = REASON_TERMINATED.equals(room.getCloseReason()) ? room.getConsultantId() : null;
    audit(room, "SUPPORT_ROOM_CLOSED", actorId);
  }

  private void beginRevocation(SupportRoom room, String reason, LocalDateTime now) {
    room.setStatus(REVOCATION_PENDING);
    room.setCloseReason(reason);
    room.setRetryAt(now);
  }

  private void audit(SupportRoom room, String event, String actorId) {
    auditEvents.save(
        HandshakeAuditEvent.builder()
            .handshakeId(room.getHandshakeId())
            .purpose("SUPPORT_ACCESS")
            .event(event)
            .actorId(actorId)
            .counterpartId(room.getConsultantId())
            .tenantId(room.getTenantId())
            .createDate(nowInUtc())
            .build());
  }

  record Revocation(String roomId, String matrixRoomId, String matrixMemberId) {}
}
