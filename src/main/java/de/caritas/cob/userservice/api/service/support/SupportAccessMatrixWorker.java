package de.caritas.cob.userservice.api.service.support;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.HandshakeAuditEvent;
import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.model.HandshakeSession.HandshakeStatus;
import de.caritas.cob.userservice.api.model.SupportAccessSession;
import de.caritas.cob.userservice.api.model.SupportAccessSession.SupportAccessSessionStatus;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeAuditEventRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeSessionRepository;
import de.caritas.cob.userservice.api.port.out.SupportAccessSessionRepository;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * All Matrix-facing work of a support session (ADR-018 §4).
 *
 * <p>Deliberately not transactional. Every step persists its result immediately so a retry resumes
 * where the last attempt stopped, and no database lock is ever held while the homeserver is being
 * called. Two invariants carry the security weight: the support identity is fresh per session and
 * never reused, and {@code CLOSED} is written only after Matrix confirmed the withdrawal.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SupportAccessMatrixWorker {

  private static final String EVENT_SESSION_STARTED = "SUPPORT_SESSION_STARTED";
  private static final String EVENT_SESSION_CLOSED = "SUPPORT_SESSION_CLOSED";
  private static final String EVENT_PROVISIONING_FAILED = "SUPPORT_PROVISIONING_FAILED";

  private final @NonNull SupportAccessSessionRepository sessionRepository;
  private final @NonNull HandshakeSessionRepository handshakeSessionRepository;
  private final @NonNull HandshakeAuditEventRepository auditRepository;
  private final @NonNull AdminRepository adminRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull MatrixConfig matrixConfig;

  @Value("${support.session-ttl-hours:4}")
  private long sessionTtlHours;

  @Value("${support.provisioning.max-attempts:5}")
  private int maxProvisioningAttempts;

  // ---------------------------------------------------------------- provisioning

  /** Creates or resumes the fresh encrypted 1:1 room for a confirmed SUPPORT_ACCESS handshake. */
  public void provision(String handshakeId) {
    var handshake =
        handshakeSessionRepository
            .findById(handshakeId)
            .orElseThrow(
                () -> new IllegalStateException("Handshake %s not found".formatted(handshakeId)));
    if (handshake.getStatus() != HandshakeStatus.CONFIRMED) {
      throw new IllegalStateException(
          "Handshake %s is %s, not confirmed".formatted(handshakeId, handshake.getStatus()));
    }

    var session = openSessionFor(handshake);
    if (session.getStatus() != SupportAccessSessionStatus.PROVISIONING) {
      // Redelivery of an already finished job: nothing left to do, and definitely no second room.
      return;
    }

    session.setProvisioningAttempts(session.getProvisioningAttempts() + 1);
    session = sessionRepository.saveAndFlush(session);
    try {
      buildRoom(session, handshake);
    } catch (RuntimeException | Error e) {
      if (session.getProvisioningAttempts() >= maxProvisioningAttempts) {
        giveUpProvisioning(session, e);
        // Swallowed on purpose: the session is terminal and visible to operations, so retrying the
        // job would only produce noise.
        return;
      }
      throw e;
    }
  }

  private SupportAccessSession openSessionFor(HandshakeSession handshake) {
    var existing = sessionRepository.findByHandshakeId(handshake.getId());
    if (existing.isPresent()) {
      return existing.get();
    }
    var now = nowInUtc();
    var session =
        SupportAccessSession.builder()
            .id(UUID.randomUUID().toString())
            .handshakeId(handshake.getId())
            .supportAdminId(handshake.getInitiatorId())
            .consultantId(handshake.getCounterpartId())
            .agencyId(handshake.getAgencyId())
            .tenantId(handshake.getTenantId())
            .status(SupportAccessSessionStatus.PROVISIONING)
            .activeLeaseKey(
                SupportAccessSession.leaseKeyOf(
                    handshake.getInitiatorId(),
                    handshake.getCounterpartId(),
                    handshake.getAgencyId()))
            .createDate(now)
            .expiryDate(now.plusHours(sessionTtlHours))
            .build();
    try {
      return sessionRepository.saveAndFlush(session);
    } catch (DataIntegrityViolationException e) {
      // Either the handshake already has a session or the pair already holds a running lease. Both
      // are exactly what the unique indexes are there to prevent; resolve to the existing row.
      return sessionRepository
          .findByHandshakeId(handshake.getId())
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "A support session already exists for %s/%s/%s"
                          .formatted(
                              handshake.getInitiatorId(),
                              handshake.getCounterpartId(),
                              handshake.getAgencyId()),
                      e));
    }
  }

  private void buildRoom(SupportAccessSession initial, HandshakeSession handshake) {
    var session = initial;
    final var sessionId = session.getId();
    final var supportAdminId = session.getSupportAdminId();
    final var consultantId = session.getConsultantId();
    var supportAdmin =
        adminRepository
            .findById(supportAdminId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Support admin %s not found".formatted(supportAdminId)));
    if (supportAdmin.getType() != Admin.AdminType.SUPPORT) {
      throw new IllegalStateException("Handshake initiator is not a Global Support Admin");
    }
    var consultant =
        consultantRepository
            .findByIdAndDeleteDateIsNull(consultantId)
            .orElseThrow(
                () -> new IllegalStateException("Consultant %s not found".formatted(consultantId)));
    var consultantMatrixId = consultant.getMatrixUserId();
    if (StringUtils.isBlank(consultantMatrixId)) {
      throw new IllegalStateException(
          "Consultant %s has no Matrix identity; support session impossible"
              .formatted(consultant.getId()));
    }

    if (session.getSupportAdminMatrixId() == null) {
      session.setSupportAdminMatrixId(createSessionIdentity(sessionId));
      session = sessionRepository.saveAndFlush(session);
    }
    var supportMatrixId = session.getSupportAdminMatrixId();
    var supportToken = matrixSynapseService.loginAsUserAccessToken(supportMatrixId);

    if (session.getMatrixRoomId() == null) {
      // Resolve first: if a previous attempt created the room but died before persisting the id,
      // the deterministic alias recovers it instead of leaving an orphan behind.
      var roomId = resolveOrCreateRoom("support-" + sessionId, supportToken, sessionId);
      session.setMatrixRoomId(roomId);
      session = sessionRepository.saveAndFlush(session);
    }
    var roomId = session.getMatrixRoomId();

    invite(roomId, consultantMatrixId, supportToken, sessionId);
    var consultantToken = matrixSynapseService.loginAsUserAccessToken(consultantMatrixId);
    if (!matrixSynapseService.joinRoom(roomId, consultantToken)) {
      throw new IllegalStateException(
          "Consultant could not join Matrix room for session %s".formatted(sessionId));
    }
    requireExactMembership(roomId, supportMatrixId, consultantMatrixId);

    var now = nowInUtc();
    session.setStatus(SupportAccessSessionStatus.ACTIVE);
    session.setExpiryDate(now.plusHours(sessionTtlHours));
    session.setLastError(null);
    audit(sessionRepository.saveAndFlush(session), EVENT_SESSION_STARTED, handshake.getPurpose());
  }

  private String resolveOrCreateRoom(String roomAlias, String supportToken, String sessionId) {
    try {
      var existing = matrixSynapseService.resolveRoomAlias(roomAlias, supportToken);
      if (existing.isPresent()) {
        return existing.get();
      }
      // Confidentiality-neutral room name: no names, no topic, no category.
      var response = matrixSynapseService.createEncryptedRoom("Support", roomAlias, supportToken);
      var roomId = response.getBody() != null ? response.getBody().getRoomId() : null;
      if (roomId == null) {
        throw new IllegalStateException(
            "Matrix room creation returned no room id for session %s".formatted(sessionId));
      }
      return roomId;
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception exception) {
      throw new IllegalStateException(
          "Matrix room creation failed for session %s".formatted(sessionId), exception);
    }
  }

  private void invite(
      String roomId, String consultantMatrixId, String supportToken, String sessionId) {
    try {
      matrixSynapseService.inviteUserToRoom(roomId, consultantMatrixId, supportToken);
    } catch (Exception exception) {
      throw new IllegalStateException(
          "Could not invite the consultant to the room of session %s".formatted(sessionId),
          exception);
    }
  }

  /**
   * A brand-new, non-administrative Matrix identity for this session only. Reusing one identity
   * across sessions would carry membership and device keys from an earlier help cycle into the next
   * one, which is exactly what "komplett von vorne" rules out.
   */
  private String createSessionIdentity(String sessionId) {
    var localpart = "support-" + sessionId.replace("-", "").substring(0, 24);
    try {
      var response =
          matrixSynapseService.createUser(localpart, UUID.randomUUID().toString(), "Support");
      if (response.getBody() != null && response.getBody().getUserId() != null) {
        return response.getBody().getUserId();
      }
    } catch (Exception e) {
      throw new IllegalStateException(
          "Could not create the support identity for session %s".formatted(sessionId), e);
    }
    return "@" + localpart + ":" + matrixConfig.getServerName();
  }

  private void giveUpProvisioning(SupportAccessSession session, Throwable cause) {
    log.error(
        "Giving up on support session {} after {} attempts",
        session.getId(),
        session.getProvisioningAttempts(),
        cause);
    // Compensation: an orphaned room must not outlive the session that failed to start.
    if (session.getMatrixRoomId() != null) {
      quietly(() -> matrixSynapseService.purgeRoom(session.getMatrixRoomId()));
    }
    if (session.getSupportAdminMatrixId() != null) {
      quietly(() -> matrixSynapseService.deactivateUser(session.getSupportAdminMatrixId()));
    }
    session.setStatus(SupportAccessSessionStatus.PROVISIONING_FAILED);
    session.setActiveLeaseKey(null);
    session.setClosedDate(nowInUtc());
    session.setLastError(StringUtils.abbreviate(String.valueOf(cause.getMessage()), 1000));
    audit(sessionRepository.saveAndFlush(session), EVENT_PROVISIONING_FAILED, null);
  }

  // ---------------------------------------------------------------- withdrawal

  /**
   * Withdraws every kind of access this session granted and only then reports it closed. Any
   * failure leaves the session REVOCATION_PENDING so it stays visible, retriable, and alertable — a
   * Matrix outage must never be able to produce a false "closed".
   */
  public void revoke(String sessionId) {
    var session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(
                () ->
                    new IllegalStateException("Support session %s not found".formatted(sessionId)));
    if (session.getStatus().isTerminal()) {
      return;
    }

    // The current Element Call integration runs the call in the support room itself, so the two ids
    // can be equal. Purging the same room twice would make the second call fail and keep the
    // session REVOCATION_PENDING forever — an outage that never happened.
    var callRoomId = session.getCallMatrixRoomId();
    var callRoomIsSeparate = callRoomId != null && !callRoomId.equals(session.getMatrixRoomId());
    if (callRoomIsSeparate && !matrixSynapseService.purgeRoom(callRoomId)) {
      throw new IllegalStateException(
          "Could not purge the call room of support session %s".formatted(sessionId));
    }
    if (session.getSupportAdminMatrixId() != null
        && !matrixSynapseService.deactivateUser(session.getSupportAdminMatrixId())) {
      throw new IllegalStateException(
          "Could not deactivate the support identity of session %s".formatted(sessionId));
    }
    if (session.getMatrixRoomId() != null) {
      if (!matrixSynapseService.purgeRoom(session.getMatrixRoomId())) {
        throw new IllegalStateException(
            "Could not purge the room of support session %s".formatted(sessionId));
      }
      requireMembershipGone(session.getMatrixRoomId(), session.getSupportAdminMatrixId());
    }

    session.setStatus(SupportAccessSessionStatus.CLOSED);
    session.setActiveLeaseKey(null);
    session.setClosedDate(nowInUtc());
    session.setLastError(null);
    audit(sessionRepository.saveAndFlush(session), EVENT_SESSION_CLOSED, null);
  }

  /** Late-registered media room on an already-ending session. */
  public void purgeCallRoom(String sessionId) {
    var session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(
                () ->
                    new IllegalStateException("Support session %s not found".formatted(sessionId)));
    var callRoomId = session.getCallMatrixRoomId();
    // Same reason as in revoke(): if the call ran in the support room there is no separate room to
    // purge, and purging it again would fail for no real reason.
    if (callRoomId == null || callRoomId.equals(session.getMatrixRoomId())) {
      return;
    }
    if (!matrixSynapseService.purgeRoom(callRoomId)) {
      throw new IllegalStateException(
          "Could not purge the call room of support session %s".formatted(sessionId));
    }
  }

  // ---------------------------------------------------------------- verification helpers

  private void requireExactMembership(String roomId, String supportMatrixId, String consultantId) {
    var members =
        matrixSynapseService
            .getRoomMembers(roomId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Could not read members of Matrix room %s".formatted(roomId)));
    if (!members.contains(supportMatrixId) || !members.contains(consultantId)) {
      throw new IllegalStateException(
          "Matrix room %s does not hold both participants yet".formatted(roomId));
    }
    var unexpected =
        members.stream()
            .filter(m -> !m.equals(supportMatrixId) && !m.equals(consultantId))
            .toList();
    if (!unexpected.isEmpty()) {
      throw new IllegalStateException(
          "Matrix room %s holds unexpected members %s".formatted(roomId, unexpected));
    }
  }

  /**
   * After a purge the room is gone, so an absent member list is the expected success case. A list
   * that still names the support identity means the withdrawal did not take effect.
   */
  private void requireMembershipGone(String roomId, String supportMatrixId) {
    var members = matrixSynapseService.getRoomMembers(roomId);
    if (members.isPresent() && supportMatrixId != null && members.get().contains(supportMatrixId)) {
      throw new IllegalStateException(
          "Support identity is still a member of Matrix room %s".formatted(roomId));
    }
  }

  private void quietly(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException e) {
      log.warn("Compensation step failed and is left to operations", e);
    }
  }

  private void audit(SupportAccessSession session, String event, Object purpose) {
    auditRepository.save(
        HandshakeAuditEvent.builder()
            .handshakeId(session.getHandshakeId())
            .purpose(purpose == null ? "SUPPORT_ACCESS" : purpose.toString())
            .event(event)
            .actorId(null)
            .counterpartId(session.getConsultantId())
            .tenantId(session.getTenantId())
            .agencyId(session.getAgencyId())
            .createDate(nowInUtc())
            .build());
  }

  /** Exposed for the expiry sweep so it does not need its own repository wiring. */
  public List<SupportAccessSession> findExpired() {
    return sessionRepository.findAllByStatusAndExpiryDateBefore(
        SupportAccessSessionStatus.ACTIVE, nowInUtc());
  }
}
