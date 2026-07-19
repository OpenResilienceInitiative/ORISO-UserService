package de.caritas.cob.userservice.api.service.support;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.HandshakeAuditEvent;
import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.model.SupportRoom;
import de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeAuditEventRepository;
import de.caritas.cob.userservice.api.port.out.SupportRoomRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Temporary Support Access room lifecycle (ADR-018 §2). The room is created only after both
 * handshake popups completed; it contains exactly the Support Admin and the Berater*in, carries
 * zero pre-existing data, dies hard at four hours (or earlier on the Berater*in's termination) and
 * is never reused — "komplett von vorne" on every cycle.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SupportRoomService {

  private static final String EVENT_ROOM_CREATED = "SUPPORT_ROOM_CREATED";
  private static final String EVENT_ROOM_CLOSED = "SUPPORT_ROOM_CLOSED";
  private static final String REASON_EXPIRED = "EXPIRED";
  private static final String REASON_TERMINATED = "TERMINATED";

  private final @NonNull SupportRoomRepository supportRoomRepository;
  private final @NonNull HandshakeAuditEventRepository handshakeAuditEventRepository;
  private final @NonNull AdminRepository adminRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull MatrixConfig matrixConfig;

  @Value("${support.room-ttl-hours:4}")
  private long roomTtlHours;

  /**
   * Creates the fresh encrypted 1:1 room for a confirmed SUPPORT_ACCESS handshake. Any failure here
   * propagates — the handshake confirmation rolls back and no half-created lease remains.
   */
  @Transactional
  public SupportRoom createForConfirmedHandshake(HandshakeSession session) {
    var supportAdmin =
        adminRepository
            .findById(session.getInitiatorId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Support admin %s of handshake %s not found"
                            .formatted(session.getInitiatorId(), session.getId())));
    var consultant =
        consultantRepository
            .findByIdAndDeleteDateIsNull(session.getCounterpartId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Consultant %s of handshake %s not found"
                            .formatted(session.getCounterpartId(), session.getId())));
    var consultantMatrixId = consultant.getMatrixUserId();
    if (consultantMatrixId == null || consultantMatrixId.isBlank()) {
      throw new IllegalStateException(
          "Consultant %s has no Matrix identity; support room impossible"
              .formatted(consultant.getId()));
    }

    try {
      var supportAdminMatrixId = resolveSupportAdminMatrixId(supportAdmin);
      var supportAdminToken = matrixSynapseService.loginAsUserAccessToken(supportAdminMatrixId);
      // Confidentiality-neutral room name: no names, no topic, no category (ADR-012 §9 spirit).
      var roomResponse = matrixSynapseService.createRoom("Support", null, supportAdminToken);
      var roomId = roomResponse.getBody() != null ? roomResponse.getBody().getRoomId() : null;
      if (roomId == null) {
        throw new IllegalStateException(
            "Matrix room creation returned no room id for handshake %s".formatted(session.getId()));
      }
      matrixSynapseService.inviteUserToRoom(roomId, consultantMatrixId, supportAdminToken);
      var consultantToken = matrixSynapseService.loginAsUserAccessToken(consultantMatrixId);
      matrixSynapseService.joinRoom(roomId, consultantToken);

      var now = LocalDateTime.now();
      var room =
          SupportRoom.builder()
              .id(UUID.randomUUID().toString())
              .handshakeId(session.getId())
              .matrixRoomId(roomId)
              .supportAdminId(supportAdmin.getId())
              .supportAdminMatrixId(supportAdminMatrixId)
              .consultantId(consultant.getId())
              .status(SupportRoomStatus.ACTIVE)
              .createDate(now)
              .expiryDate(now.plusHours(roomTtlHours))
              .tenantId(session.getTenantId())
              .build();
      room = supportRoomRepository.save(room);
      audit(room, EVENT_ROOM_CREATED, null);
      return room;
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(
          "Support room creation failed for handshake %s".formatted(session.getId()), e);
    }
  }

  /** The Berater*in ends the session early — same hard close as the four-hour cutoff. */
  @Transactional
  public void terminate(AuthenticatedUser caller, String roomId) {
    var room =
        supportRoomRepository
            .findById(roomId)
            .orElseThrow(
                () -> new BadRequestException(String.format("Unknown support room %s", roomId)));
    if (!room.getConsultantId().equals(caller.getUserId())) {
      throw new ForbiddenException(
          String.format("User %s may not terminate support room %s", caller.getUserId(), roomId));
    }
    if (room.getStatus() != SupportRoomStatus.ACTIVE) {
      throw new BadRequestException(String.format("Support room %s is not active", roomId));
    }
    close(room, REASON_TERMINATED, caller.getUserId());
  }

  @Transactional(readOnly = true)
  public List<SupportRoomItem> activeFor(AuthenticatedUser caller) {
    return supportRoomRepository
        .findAllByStatusAndConsultantIdOrStatusAndSupportAdminId(
            SupportRoomStatus.ACTIVE,
            caller.getUserId(),
            SupportRoomStatus.ACTIVE,
            caller.getUserId())
        .stream()
        .map(SupportRoomItem::of)
        .toList();
  }

  /** ADR-018 hard cutoff: at four hours the lease ends even if the homeserver hiccups. */
  @Scheduled(fixedDelayString = "${support.room-sweep-delay-ms:60000}")
  @Transactional
  public void sweepExpired() {
    supportRoomRepository
        .findAllByStatusAndExpiryDateBefore(SupportRoomStatus.ACTIVE, LocalDateTime.now())
        .forEach(room -> close(room, REASON_EXPIRED, null));
  }

  private void close(SupportRoom room, String reason, String actorId) {
    try {
      var supportAdminToken =
          matrixSynapseService.loginAsUserAccessToken(room.getSupportAdminMatrixId());
      matrixSynapseService.leaveRoom(room.getMatrixRoomId(), supportAdminToken);
    } catch (Exception e) {
      log.error(
          "Could not remove support admin from Matrix room {} while closing support room {} — closing the lease anyway",
          room.getMatrixRoomId(),
          room.getId(),
          e);
    }
    room.setStatus(SupportRoomStatus.CLOSED);
    room.setCloseReason(reason);
    room.setClosedDate(LocalDateTime.now());
    supportRoomRepository.save(room);
    audit(room, EVENT_ROOM_CLOSED, actorId);
  }

  private String resolveSupportAdminMatrixId(Admin supportAdmin) throws Exception {
    var localpart = supportAdmin.getUsername().toLowerCase();
    if (!matrixSynapseService.userExists(localpart)) {
      var response =
          matrixSynapseService.createUser(
              supportAdmin.getUsername(),
              UUID.randomUUID().toString(),
              supportAdmin.getFirstName() + " " + supportAdmin.getLastName());
      if (response.getBody() != null && response.getBody().getUserId() != null) {
        return response.getBody().getUserId();
      }
    }
    return "@" + localpart + ":" + matrixConfig.getServerName();
  }

  private void audit(SupportRoom room, String event, String actorId) {
    handshakeAuditEventRepository.save(
        HandshakeAuditEvent.builder()
            .handshakeId(room.getHandshakeId())
            .purpose("SUPPORT_ACCESS")
            .event(event)
            .actorId(actorId)
            .counterpartId(room.getConsultantId())
            .tenantId(room.getTenantId())
            .createDate(LocalDateTime.now())
            .build());
  }

  @Getter
  public static class SupportRoomItem {
    private String id;
    private String matrixRoomId;
    private String supportAdminId;
    private String consultantId;
    private LocalDateTime expiryDate;

    static SupportRoomItem of(SupportRoom room) {
      var item = new SupportRoomItem();
      item.id = room.getId();
      item.matrixRoomId = room.getMatrixRoomId();
      item.supportAdminId = room.getSupportAdminId();
      item.consultantId = room.getConsultantId();
      item.expiryDate = room.getExpiryDate();
      return item;
    }
  }
}
