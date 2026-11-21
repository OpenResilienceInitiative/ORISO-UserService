package de.caritas.cob.userservice.api.service.support;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.model.SupportRoom;
import de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus;
import de.caritas.cob.userservice.api.model.SupportRoomCreationIntent;
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

  private static final Duration SWEEP_BUDGET = Duration.ofSeconds(20);

  private final @NonNull SupportRoomRepository supportRoomRepository;
  private final @NonNull SupportRoomLifecycle lifecycle;
  private final @NonNull SupportRoomProvisioningLedger provisioning;
  private final @NonNull AdminRepository adminRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull MatrixConfig matrixConfig;

  @Value("${support.room-ttl-hours:4}")
  private long roomTtlHours;

  @Value("${support.room-sweep-batch-size:20}")
  private int sweepBatchSize;

  /** Reuses only the verified room reserved by this authenticated confirmation. */
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
      if (isBlank(supportAdminToken) || supportAdminMatrixId.equals(consultantMatrixId)) {
        throw new IllegalStateException("Support room requires two distinct Matrix identities");
      }
      var reservation =
          provisioning.reserve(
              session.getId(),
              supportAdmin.getId(),
              supportAdminMatrixId,
              consultant.getId(),
              consultantMatrixId,
              session.getTenantId(),
              session.getExpiryDate());
      var intent = reservation.intent();
      var matrixRoomId = verifyOrRecoverRoom(reservation, supportAdminToken);
      if (!provisioning.bindForConfirmation(intent, matrixRoomId)) {
        throw new IllegalStateException("Support room provisioning grace has expired");
      }

      if (!matrixSynapseService.isRoomEncrypted(matrixRoomId).orElse(false)) {
        throw new IllegalStateException("Matrix did not confirm support room encryption");
      }
      if (!matrixSynapseService.ensureSupportRoomModeration(matrixRoomId, supportAdminToken)) {
        throw new ServiceUnavailableException("Support room restrictions could not be verified");
      }
      confirmParticipants(
          matrixRoomId, supportAdminMatrixId, consultantMatrixId, supportAdminToken);
      var room = lifecycle.activate(intent.getId(), roomTtlHours);
      provisioning.forgetConfirmed(session.getId());
      return room;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Support room creation failed", e);
    }
  }

  private String verifyOrRecoverRoom(
      SupportRoomProvisioningLedger.Reservation reservation, String creatorToken) {
    var intent = reservation.intent();
    String roomId = null;
    if (reservation.firstAttempt()) {
      try {
        roomId =
            matrixSynapseService.createSupportRoom(
                intent.getRoomAlias(), intent.getNonce(), creatorToken);
      } catch (Exception ambiguous) {
        // A lost response can leave a created room. Never issue another POST for this intent.
        log.warn(
            "Support room creation result is uncertain ({})", ambiguous.getClass().getSimpleName());
      }
    }
    if (isBlank(roomId)) {
      var lookup = matrixSynapseService.resolveRoomAlias(fullAlias(intent));
      if (lookup != null && lookup.known()) {
        roomId = lookup.roomId();
      }
    }
    if (isBlank(roomId) || !hasExpectedOrigin(intent, roomId, creatorToken)) {
      throw new ServiceUnavailableException("Matrix has not confirmed the reserved support room");
    }
    return roomId;
  }

  private String fullAlias(SupportRoomCreationIntent intent) {
    return "#" + intent.getRoomAlias() + ":" + matrixConfig.getServerName();
  }

  private boolean hasExpectedOrigin(
      SupportRoomCreationIntent intent, String roomId, String creatorToken) {
    return matrixSynapseService.verifySupportRoomOrigin(
        roomId, intent.getSupportAdminMatrixId(), intent.getNonce(), creatorToken);
  }

  private void confirmParticipants(
      String matrixRoomId,
      String supportAdminMatrixId,
      String consultantMatrixId,
      String supportAdminToken)
      throws Exception {
    var expected = Set.of(supportAdminMatrixId, consultantMatrixId);
    var before = matrixSynapseService.getRoomMembers(matrixRoomId).map(HashSet::new);
    if (before.filter(expected::equals).isPresent()) {
      return;
    }
    if (before.filter(Set.of(supportAdminMatrixId)::equals).isEmpty()) {
      throw new ServiceUnavailableException(
          "Matrix did not confirm the expected support membership");
    }
    matrixSynapseService.inviteUserToRoom(matrixRoomId, consultantMatrixId, supportAdminToken);
    var consultantToken = matrixSynapseService.loginAsUserAccessToken(consultantMatrixId);
    if (isBlank(consultantToken) || !matrixSynapseService.joinRoom(matrixRoomId, consultantToken)) {
      throw new IllegalStateException(
          "Matrix did not confirm the consultant joined the support room");
    }
    if (matrixSynapseService
        .getRoomMembers(matrixRoomId)
        .map(HashSet::new)
        .filter(expected::equals)
        .isEmpty()) {
      throw new IllegalStateException("Matrix did not confirm both support room participants");
    }
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
      lifecycle.claimClosure(roomId).ifPresent(this::withdrawAndConfirm);
    }
    for (var handshakeId : provisioning.expiredIds(nowInUtc(), sweepBatchSize)) {
      if (System.nanoTime() - startedAt >= SWEEP_BUDGET.toNanos()) {
        return;
      }
      provisioning.claimExpired(handshakeId).ifPresent(this::recoverExpiredIntent);
    }
  }

  private void recoverExpiredIntent(SupportRoomCreationIntent intent) {
    try {
      var lookup = matrixSynapseService.resolveRoomAlias(fullAlias(intent));
      if (lookup == null || !lookup.known() || isBlank(lookup.roomId())) {
        return; // The provider may still be finishing the original request.
      }
      var creatorToken =
          matrixSynapseService.loginAsUserAccessToken(intent.getSupportAdminMatrixId());
      if (!isBlank(creatorToken) && hasExpectedOrigin(intent, lookup.roomId(), creatorToken)) {
        provisioning.bindExpiredForCleanup(intent, lookup.roomId());
      }
    } catch (RuntimeException failure) {
      log.warn(
          "Support room creation reconciliation remains pending ({})",
          failure.getClass().getSimpleName());
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
