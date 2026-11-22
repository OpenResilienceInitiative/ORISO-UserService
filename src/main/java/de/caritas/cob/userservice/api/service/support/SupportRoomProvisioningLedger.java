package de.caritas.cob.userservice.api.service.support;

import static de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus.ACTIVE;
import static de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus.PROVISIONING;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.model.SupportRoom;
import de.caritas.cob.userservice.api.model.SupportRoomCreationIntent;
import de.caritas.cob.userservice.api.port.out.SupportRoomCreationIntentRepository;
import de.caritas.cob.userservice.api.port.out.SupportRoomRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durably tracks a support room before Matrix has identified it. */
@Service
@RequiredArgsConstructor
class SupportRoomProvisioningLedger {

  private static final int PROVISIONING_GRACE_MINUTES = 3;
  private static final List<Integer> RECOVERY_BACKOFF_MINUTES = List.of(2, 4, 8, 16, 30);

  private final SupportRoomCreationIntentRepository intents;
  private final SupportRoomRepository rooms;
  private final Clock clock;

  /**
   * The authorised caller holds the handshake lock until confirmation commits or rolls back. Read
   * committed keeps the write lock on an existing intent without taking a gap lock for a new one.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
  public Reservation reserve(
      String handshakeId,
      String supportAdminId,
      String supportAdminMatrixId,
      String consultantId,
      String consultantMatrixId,
      Long tenantId,
      LocalDateTime expiresAt) {
    var deadline = expiresAt.truncatedTo(ChronoUnit.MICROS);
    if (isBlank(supportAdminMatrixId)
        || isBlank(consultantMatrixId)
        || supportAdminMatrixId.equals(consultantMatrixId)) {
      throw new IllegalStateException("Support room creation scope is no longer valid");
    }
    var saved = intents.lockByHandshakeId(handshakeId);
    var now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    if (!deadline.isAfter(now)) {
      throw new IllegalStateException("Support room creation scope is no longer valid");
    }
    if (saved.isPresent()) {
      var intent = saved.get();
      if (!intent.getSupportAdminId().equals(supportAdminId)
          || !intent.getSupportAdminMatrixId().equals(supportAdminMatrixId)
          || !intent.getConsultantId().equals(consultantId)
          || !intent.getConsultantMatrixId().equals(consultantMatrixId)
          || !Objects.equals(intent.getTenantId(), tenantId)
          || !intent.getExpiresAt().equals(deadline)) {
        throw new IllegalStateException("Support room creation scope changed");
      }
      return new Reservation(intent, false);
    }

    var graceDeadline = now.plusMinutes(PROVISIONING_GRACE_MINUTES);
    var provisioningDeadline = deadline.isBefore(graceDeadline) ? deadline : graceDeadline;
    var intent =
        SupportRoomCreationIntent.builder()
            .handshakeId(handshakeId)
            .id(UUID.randomUUID().toString())
            .nonce(UUID.randomUUID().toString())
            .supportAdminId(supportAdminId)
            .supportAdminMatrixId(supportAdminMatrixId)
            .consultantId(consultantId)
            .consultantMatrixId(consultantMatrixId)
            .tenantId(tenantId)
            .expiresAt(deadline)
            .provisioningDeadline(provisioningDeadline)
            .retryAt(deadline)
            .build();
    return new Reservation(intents.save(intent), true);
  }

  /**
   * Record the verified room for withdrawal and report whether it is still eligible for admission.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean bindForConfirmation(SupportRoomCreationIntent proof, String matrixRoomId) {
    var intent = requireCurrent(proof);
    var now = LocalDateTime.now(clock);
    if (!intent.getExpiresAt().isAfter(now)) {
      throw new IllegalStateException("Support room creation is no longer current");
    }
    var room = bindRoom(intent, matrixRoomId, now);
    return room.getExpiryDate().isAfter(now);
  }

  @Transactional(readOnly = true)
  public List<String> expiredIds(LocalDateTime now, int max) {
    return intents.findDueIds(now, PageRequest.of(0, max));
  }

  /** Lease the recovery probe without holding database locks during Matrix calls. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<SupportRoomCreationIntent> claimExpired(String handshakeId) {
    var intent = intents.lockByHandshakeId(handshakeId).orElse(null);
    var now = LocalDateTime.now(clock);
    if (intent == null || intent.getExpiresAt().isAfter(now) || intent.getRetryAt().isAfter(now)) {
      return Optional.empty();
    }

    var knownRoom = rooms.findById(intent.getId());
    if (knownRoom.filter(room -> owns(intent, room)).isPresent()) {
      // Its separately committed lifecycle row already survives a confirmation rollback.
      intents.delete(intent);
      return Optional.empty();
    }

    intent.setRetryAt(now.plusMinutes(1).truncatedTo(ChronoUnit.MICROS));
    return knownRoom.isEmpty() ? Optional.of(intent) : Optional.empty();
  }

  /** Back off an inconclusive probe while its claim is still current. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deferUnresolvedRecovery(SupportRoomCreationIntent proof) {
    var intent = intents.lockByHandshakeId(proof.getHandshakeId()).orElse(null);
    var now = LocalDateTime.now(clock);
    if (intent == null
        || !matchesIntent(intent, proof)
        || !hasCurrentRecoveryLease(intent, proof, now)) {
      return;
    }

    var step = Math.min(intent.getRecoveryProbeFailures(), RECOVERY_BACKOFF_MINUTES.size() - 1);
    intent.setRecoveryProbeFailures(step + 1);
    intent.setRetryAt(
        now.plusMinutes(RECOVERY_BACKOFF_MINUTES.get(step)).truncatedTo(ChronoUnit.MICROS));
  }

  /** Give the existing withdrawal sweep the verified room and retire the creation intent. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void bindExpiredForCleanup(SupportRoomCreationIntent proof, String matrixRoomId) {
    var intent = requireCurrent(proof);
    var now = LocalDateTime.now(clock);
    if (!hasCurrentRecoveryLease(intent, proof, now)) {
      throw new IllegalStateException("Support room recovery claim is no longer current");
    }
    bindRoom(intent, matrixRoomId, now);
    intents.delete(intent);
  }

  /** The normal confirmation path retires its intent in the same transaction as activation. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void forgetConfirmed(String handshakeId) {
    intents
        .lockByHandshakeId(handshakeId)
        .ifPresent(
            intent -> {
              var room = rooms.findById(intent.getId()).orElseThrow();
              if (room.getStatus() != ACTIVE || !owns(intent, room)) {
                throw new IllegalStateException("Support room activation is not established");
              }
              intents.delete(intent);
            });
  }

  private SupportRoomCreationIntent requireCurrent(SupportRoomCreationIntent proof) {
    var intent = intents.lockByHandshakeId(proof.getHandshakeId()).orElseThrow();
    if (!matchesIntent(intent, proof)) {
      throw new IllegalStateException("Support room creation intent changed");
    }
    return intent;
  }

  private static boolean matchesIntent(
      SupportRoomCreationIntent intent, SupportRoomCreationIntent proof) {
    return intent.getId().equals(proof.getId()) && intent.getNonce().equals(proof.getNonce());
  }

  private static boolean hasCurrentRecoveryLease(
      SupportRoomCreationIntent intent, SupportRoomCreationIntent proof, LocalDateTime now) {
    return !intent.getExpiresAt().isAfter(now)
        && intent.getRetryAt().isAfter(now)
        && intent.getRetryAt().equals(proof.getRetryAt());
  }

  private SupportRoom bindRoom(
      SupportRoomCreationIntent intent, String matrixRoomId, LocalDateTime now) {
    if (isBlank(matrixRoomId)) {
      throw new IllegalStateException("Support room binding requires a Matrix room ID");
    }
    var saved = rooms.findById(intent.getId());
    if (saved.isPresent()) {
      var room = saved.get();
      if (!owns(intent, room)
          || room.getStatus() != PROVISIONING
          || !matrixRoomId.equals(room.getMatrixRoomId())) {
        throw new IllegalStateException("Support room is already bound to a different scope");
      }
      return room;
    }
    if (rooms.existsByMatrixRoomId(matrixRoomId)) {
      throw new IllegalStateException("Matrix room is already bound to another support room");
    }
    // An older intent has no proof of the grace deadline. Keep its room for withdrawal.
    var deadline = intent.getProvisioningDeadline();
    return rooms.save(
        SupportRoom.builder()
            .id(intent.getId())
            .handshakeId(intent.getHandshakeId())
            .matrixRoomId(matrixRoomId)
            .supportAdminId(intent.getSupportAdminId())
            .supportAdminMatrixId(intent.getSupportAdminMatrixId())
            .consultantId(intent.getConsultantId())
            .status(PROVISIONING)
            .createDate(now)
            .expiryDate(deadline == null ? now : deadline)
            .tenantId(intent.getTenantId())
            .build());
  }

  private boolean owns(SupportRoomCreationIntent intent, SupportRoom room) {
    return !isBlank(room.getMatrixRoomId())
        && intent.getHandshakeId().equals(room.getHandshakeId())
        && intent.getSupportAdminId().equals(room.getSupportAdminId())
        && intent.getSupportAdminMatrixId().equals(room.getSupportAdminMatrixId())
        && intent.getConsultantId().equals(room.getConsultantId())
        && Objects.equals(intent.getTenantId(), room.getTenantId());
  }

  record Reservation(SupportRoomCreationIntent intent, boolean firstAttempt) {}
}
