package de.caritas.cob.userservice.api.service.support;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.model.SupportRoom;
import de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SupportRoomRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
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

  private static final Duration PROVISIONING_GRACE = Duration.ofMinutes(3);
  private static final Duration SWEEP_BUDGET = Duration.ofSeconds(20);

  private final @NonNull SupportRoomRepository supportRoomRepository;
  private final @NonNull SupportRoomLifecycle lifecycle;
  private final @NonNull AdminRepository adminRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull MatrixConfig matrixConfig;

  @Value("${support.room-ttl-hours:4}")
  private long roomTtlHours;

  @Value("${support.room-sweep-batch-size:20}")
  private int sweepBatchSize;

  /** Creates a verified support room; failed confirmation leaves a durable cleanup marker. */
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

    var roomId = UUID.randomUUID().toString();
    try {
      var supportAdminMatrixId = resolveSupportAdminMatrixId(supportAdmin);
      var supportAdminToken = matrixSynapseService.loginAsUserAccessToken(supportAdminMatrixId);
      if (isBlank(supportAdminToken) || supportAdminMatrixId.equals(consultantMatrixId)) {
        throw new IllegalStateException("Support room requires two distinct Matrix identities");
      }
      // Confidentiality-neutral room name: no names, no topic, no category.
      var roomResponse = matrixSynapseService.createRoom("Support", null, supportAdminToken);
      var matrixRoomId = roomResponse.getBody() != null ? roomResponse.getBody().getRoomId() : null;
      if (isBlank(matrixRoomId)) {
        throw new IllegalStateException("Matrix did not identify the created support room");
      }
      var now = nowInUtc();
      lifecycle.rememberProvisional(
          SupportRoom.builder()
              .id(roomId)
              .handshakeId(session.getId())
              .matrixRoomId(matrixRoomId)
              .supportAdminId(supportAdmin.getId())
              .supportAdminMatrixId(supportAdminMatrixId)
              .consultantId(consultant.getId())
              .status(SupportRoomStatus.PROVISIONING)
              .createDate(now)
              .expiryDate(now.plus(PROVISIONING_GRACE))
              .tenantId(session.getTenantId())
              .build());

      if (!matrixSynapseService.isRoomEncrypted(matrixRoomId).orElse(false)) {
        throw new IllegalStateException("Matrix did not confirm support room encryption");
      }
      matrixSynapseService.inviteUserToRoom(matrixRoomId, consultantMatrixId, supportAdminToken);
      var consultantToken = matrixSynapseService.loginAsUserAccessToken(consultantMatrixId);
      if (isBlank(consultantToken)
          || !matrixSynapseService.joinRoom(matrixRoomId, consultantToken)) {
        throw new IllegalStateException(
            "Matrix did not confirm the consultant joined the support room");
      }
      var expectedMembers = Set.of(supportAdminMatrixId, consultantMatrixId);
      var members = matrixSynapseService.getRoomMembers(matrixRoomId);
      if (members.map(HashSet::new).filter(expectedMembers::equals).isEmpty()) {
        throw new IllegalStateException("Matrix did not confirm both support room participants");
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Support room creation failed", e);
    }
    return lifecycle.activate(roomId, roomTtlHours);
  }

  /** Ends the app lease now. Matrix withdrawal runs from the persisted pending row. */
  public void terminate(AuthenticatedUser caller, String roomId) {
    lifecycle.terminate(roomId, caller.getUserId());
  }

  @Transactional(readOnly = true)
  public List<SupportRoomItem> activeFor(AuthenticatedUser caller) {
    var now = nowInUtc();
    return supportRoomRepository
        .findAllByStatusAndConsultantIdOrStatusAndSupportAdminId(
            SupportRoomStatus.ACTIVE,
            caller.getUserId(),
            SupportRoomStatus.ACTIVE,
            caller.getUserId())
        .stream()
        .filter(room -> room.getExpiryDate().isAfter(now))
        .map(SupportRoomItem::of)
        .toList();
  }

  /** Reconciles due withdrawals; the app lease ends at its cutoff even during an outage. */
  @Scheduled(fixedDelayString = "${support.room-sweep-delay-ms:60000}")
  public void sweepExpired() {
    long startedAt = System.nanoTime();
    var due =
        supportRoomRepository.findClosureDue(
            List.of(SupportRoomStatus.ACTIVE, SupportRoomStatus.PROVISIONING),
            SupportRoomStatus.REVOCATION_PENDING,
            nowInUtc(),
            PageRequest.of(0, sweepBatchSize));
    for (var roomId : due) {
      if (System.nanoTime() - startedAt >= SWEEP_BUDGET.toNanos()) {
        return;
      }
      lifecycle.claimClosure(roomId, nowInUtc()).ifPresent(this::withdrawAndConfirm);
    }
  }

  private void withdrawAndConfirm(SupportRoomLifecycle.Revocation work) {
    try {
      var token = matrixSynapseService.loginAsUserAccessToken(work.matrixMemberId());
      if (!isBlank(token)) {
        matrixSynapseService.leaveRoom(work.matrixRoomId(), token);
      }
      // A generic leave acknowledgement is insufficient; require the authoritative room state.
      if (matrixSynapseService
          .isOutOfRoom(work.matrixRoomId(), work.matrixMemberId())
          .orElse(false)) {
        lifecycle.confirmClosure(work.roomId());
        return;
      }
      log.warn("Support room withdrawal {} remains pending", work.roomId());
    } catch (RuntimeException failure) {
      log.warn(
          "Support room withdrawal {} remains pending ({})",
          work.roomId(),
          failure.getClass().getSimpleName());
    }
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
