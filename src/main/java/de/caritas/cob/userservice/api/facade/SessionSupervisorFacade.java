package de.caritas.cob.userservice.api.facade;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.supervision.SupervisionConsent;
import de.caritas.cob.userservice.api.supervision.SupervisionNotes;
import de.caritas.cob.userservice.api.supervision.SupervisionReason;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Facade for managing session supervisors. Handles adding/removing supervisors and Matrix room
 * operations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionSupervisorFacade {

  private final @NonNull SessionSupervisorRepository sessionSupervisorRepository;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull UserAccountService userAccountService;
  private final @NonNull IdentityClient identityClient;

  /**
   * ADR-008 item 4: the current request's authenticated user, used to check the Berater-Admin
   * (agency-admin) role. Frank confirmed (2026-07-04) that attaching/detaching a supervisor is
   * exactly the counsellor-admin's role, configured in the Admin board. Injected the same way
   * {@code AssignSessionFacade} does (a request-scoped proxy resolved per request), so it boots
   * fine.
   */
  private final @NonNull AuthenticatedUser authenticatedUser;

  /**
   * ADR-008 item 4: when true, only the assigned consultant and agency admins may add/remove
   * supervisors (a plain same-agency consultant is denied). This is the server side of the
   * agency-admin ("Berater-Admin") Admin-board policy Frank described. Defaults false → assigned OR
   * same agency (current behaviour), so runtime is unchanged until an agency admin turns it on.
   * Field-injected (not a constructor arg) so existing @RequiredArgsConstructor callers and unit
   * tests are unaffected; tests set it via ReflectionTestUtils.
   */
  @Value("${supervision.restrict-add-to-assigned-consultant:false}")
  private boolean restrictAddToAssignedConsultant;

  private static final int SUPERVISOR_POWER_LEVEL = 10; // Read-only observer level

  /**
   * Add a supervisor to a session. The supervisor must be from the same agency as the session.
   *
   * <p>ADR-008 item 4 (DRAFT): {@code reasonCode} + {@code notes} (the justification) + the
   * computed consent state are recorded together in the existing {@code notes} column via {@link
   * SupervisionNotes}. {@code reasonCode} is OPTIONAL and fully backward-compatible: when a caller
   * supplies one it is validated and consent is computed (the new explicit path); when it is
   * absent/blank the legacy path applies (reason null, consent NOT_REQUIRED, justification stored
   * as-is) so the running frontend — which does not yet send a reason — keeps working. The
   * productionised version makes {@code reasonCode} required once the paired FE reason-picker
   * ships.
   *
   * @param sessionId the session ID
   * @param supervisorConsultantId the consultant ID to add as supervisor
   * @param addedByConsultant the consultant adding the supervisor
   * @param reasonCode optional ADR-008 supervision reason code (validated when present)
   * @param notes the justification for supervision (required only when a reasonCode is supplied)
   * @return the created SessionSupervisor
   */
  @Transactional
  public SessionSupervisor addSupervisor(
      Long sessionId,
      String supervisorConsultantId,
      Consultant addedByConsultant,
      String reasonCode,
      String notes) {
    // ADR-008 item 4 (DRAFT): resolve the reason + consent. reasonCode is optional; the app's
    // current supervisor modal does not send one yet, so a missing reason must NOT 400 (legacy
    // path). A reason that is PRESENT but unknown is a client error.
    boolean reasonSupplied = reasonCode != null && !reasonCode.isBlank();
    SupervisionReason reason = null;
    if (reasonSupplied) {
      reason =
          SupervisionReason.fromCode(reasonCode)
              .orElseThrow(() -> new BadRequestException("A valid supervision reason is required"));
      // On the explicit (with-reason) path the justification must be present.
      if (notes == null || notes.isBlank()) {
        throw new BadRequestException("A supervision justification is required");
      }
    }
    SupervisionConsent consent = SupervisionConsent.initialFor(reason);
    String encodedNotes = SupervisionNotes.encode(reason, notes, consent);

    // Get session
    Session session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new NotFoundException("Session not found: " + sessionId));

    // Verify addedByConsultant has permission (must be assigned consultant or from
    // same agency)
    log.info(
        "Checking permission for consultant {} to add supervisor to session {} (agencyId: {})",
        addedByConsultant.getId(),
        sessionId,
        session.getAgencyId());
    if (!hasPermissionToManageSupervisors(session, addedByConsultant)) {
      log.warn(
          "Permission denied: Consultant {} does not have permission to add supervisors to session {}",
          addedByConsultant.getId(),
          sessionId);
      throw new ForbiddenException(
          "Consultant does not have permission to add supervisors to this session");
    }
    log.info(
        "Permission granted for consultant {} to add supervisor to session {}",
        addedByConsultant.getId(),
        sessionId);

    // Get supervisor consultant
    Consultant supervisorConsultant =
        consultantRepository
            .findById(supervisorConsultantId)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "Supervisor consultant not found: " + supervisorConsultantId));

    // Verify supervisor has supervisor permission (checked via database field)
    if (!supervisorConsultant.isSupervisor()) {
      log.warn(
          "Consultant {} does not have supervisor permission (is_supervisor = false)",
          supervisorConsultantId);
      throw new BadRequestException(
          "Consultant does not have permission to be a supervisor. Only consultants with supervisor permission can be added as supervisors.");
    }

    // Verify supervisor is from same agency
    if (!areFromSameAgency(session, supervisorConsultant)) {
      throw new BadRequestException("Supervisor must be from the same agency as the session");
    }

    // Check if already supervising
    Optional<SessionSupervisor> existing =
        sessionSupervisorRepository.findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(
            sessionId, supervisorConsultantId);
    if (existing.isPresent()) {
      throw new BadRequestException("Consultant is already supervising this session");
    }

    // Get Matrix room ID
    String matrixRoomId = session.getMatrixRoomId();
    if (matrixRoomId == null || matrixRoomId.isEmpty()) {
      throw new BadRequestException("Session does not have a Matrix room");
    }

    // Get supervisor's Matrix user ID
    String supervisorMatrixUserId = supervisorConsultant.getMatrixUserId();
    if (supervisorMatrixUserId == null || supervisorMatrixUserId.isEmpty()) {
      throw new BadRequestException("Supervisor consultant does not have a Matrix user ID");
    }

    if (addedByConsultant.getMatrixUserId() == null
        || addedByConsultant.getMatrixUserId().isEmpty()) {
      throw new InternalServerErrorException(
          "Consultant adding supervisor does not have Matrix credentials");
    }

    // ADR-008 item 4: never create a second PENDING-consent request for the same supervisor on
    // the same session (the client would get duplicate disclosure prompts).
    boolean pendingAlreadyExists =
        sessionSupervisorRepository.findBySessionId(sessionId).stream()
            .filter(s -> s.getSupervisorConsultant() != null)
            .filter(s -> supervisorConsultantId.equals(s.getSupervisorConsultant().getId()))
            .anyMatch(SessionSupervisorFacade::isPendingConsent);
    if (pendingAlreadyExists) {
      throw new BadRequestException(
          "A supervision consent request for this consultant is already pending");
    }

    SessionSupervisor.SessionSupervisorBuilder builder =
        SessionSupervisor.builder()
            .session(session)
            .supervisorConsultant(supervisorConsultant)
            .addedByConsultant(addedByConsultant)
            .addedDate(LocalDateTime.now())
            .notes(encodedNotes);

    if (consent == SupervisionConsent.PENDING) {
      // CONSENT GATE (ADR-008 item 4): the ratsuchende's consent is required for this reason
      // (SAFEGUARDING_U25 / CLINICAL_OVERSIGHT) and has NOT been given yet. Record the request but
      // grant NO Matrix access — the supervisor is invited to NEITHER the client room nor the side
      // room until the client approves via the consent endpoint. For minors' cases a supervisor
      // must never observe before consent exists.
      SessionSupervisor pending = builder.isActive(false).matrixRoomId(null).build();
      SessionSupervisor saved = sessionSupervisorRepository.save(pending);
      log.info(
          "Recorded PENDING-consent supervisor {} for session {} (no Matrix access until approved)",
          supervisorConsultant.getUsername(),
          sessionId);
      return saved;
    }

    // No consent required (or legacy no-reason path) — provision Matrix access immediately.
    String sideRoomId =
        provisionSupervisorRooms(
            session, addedByConsultant, supervisorConsultant, supervisorMatrixUserId, matrixRoomId);
    SessionSupervisor saved =
        sessionSupervisorRepository.save(builder.isActive(true).matrixRoomId(sideRoomId).build());
    log.info(
        "Added supervisor {} to session {} by consultant {}",
        supervisorConsultant.getUsername(),
        sessionId,
        addedByConsultant.getUsername());
    return saved;
  }

  /**
   * Give the supervisor Matrix access: (re)use the ADR-008 side room, invite them into the client
   * room as a read-only observer, set their power level, and join them. Extracted so it can run
   * either immediately (no-consent path) or deferred, after the client approves consent.
   *
   * @return the supervision side room id the supervisor was placed in
   */
  private String provisionSupervisorRooms(
      Session session,
      Consultant addedByConsultant,
      Consultant supervisorConsultant,
      String supervisorMatrixUserId,
      String matrixRoomId) {
    String consultantToken =
        matrixSynapseService.loginAsUserAccessToken(addedByConsultant.getMatrixUserId());
    if (consultantToken == null) {
      throw new InternalServerErrorException("Failed to create consultant Matrix token");
    }

    // ADR-008: provision (or reuse) a SEPARATE supervision side room that the client is NEVER
    // invited to. Supervisor↔counsellor feedback and coordinator↔peer asides live here, so they
    // are never delivered to the client's Matrix device. Do this BEFORE touching the client room
    // so a failure leaves no half state. The supervisor still joins the client room read-only
    // (below) for observation — only their feedback moves out.
    String sideRoomId =
        ensureSupervisionSideRoom(
            session,
            addedByConsultant,
            supervisorConsultant,
            supervisorMatrixUserId,
            consultantToken);

    // Invite supervisor to the CLIENT room as a read-only observer.
    inviteSupervisorToClientRoom(
        matrixRoomId, supervisorMatrixUserId, consultantToken, supervisorConsultant, session);

    // Set supervisor power level to 10 (read-only observer)
    boolean powerLevelSet =
        matrixSynapseService.setUserPowerLevel(
            matrixRoomId, supervisorMatrixUserId, SUPERVISOR_POWER_LEVEL, consultantToken);
    if (!powerLevelSet) {
      log.warn(
          "Failed to set power level for supervisor {} in room {}, but continuing",
          supervisorConsultant.getUsername(),
          matrixRoomId);
    } else {
      log.info(
          "Set supervisor {} power level to {} in room {}",
          supervisorConsultant.getUsername(),
          SUPERVISOR_POWER_LEVEL,
          matrixRoomId);
    }

    String supervisorToken = matrixSynapseService.loginAsUserAccessToken(supervisorMatrixUserId);
    if (supervisorToken != null) {
      boolean joined = matrixSynapseService.joinRoom(matrixRoomId, supervisorToken);
      if (joined) {
        log.info(
            "Supervisor {} successfully joined Matrix room {}",
            supervisorConsultant.getUsername(),
            matrixRoomId);
      }
    }
    return sideRoomId;
  }

  /**
   * The ratsuchende's decision on a PENDING supervision-consent request (ADR-008 item 4). On
   * APPROVE the deferred Matrix provisioning runs and the supervisor becomes active; on DECLINE the
   * request is recorded as declined and the supervisor never gets room access.
   *
   * @param sessionId the session the request belongs to
   * @param supervisorId the pending {@link SessionSupervisor} row id
   * @param approve true to approve (provision + activate), false to decline
   * @return the updated {@link SessionSupervisor}
   */
  @Transactional
  public SessionSupervisor decideSupervisionConsent(
      Long sessionId, Long supervisorId, boolean approve) {
    SessionSupervisor supervisor =
        sessionSupervisorRepository
            .findById(supervisorId)
            .orElseThrow(() -> new NotFoundException("Supervisor not found: " + supervisorId));
    if (supervisor.getSession() == null || !supervisor.getSession().getId().equals(sessionId)) {
      throw new BadRequestException("Supervisor does not belong to this session");
    }
    if (!isPendingConsent(supervisor)) {
      throw new BadRequestException("No pending supervision-consent request for this supervisor");
    }

    SupervisionNotes.Payload payload = SupervisionNotes.decode(supervisor.getNotes());
    SupervisionReason reason = SupervisionReason.fromCode(payload.reasonCode).orElse(null);

    if (approve) {
      Session session = supervisor.getSession();
      String sideRoomId =
          provisionSupervisorRooms(
              session,
              supervisor.getAddedByConsultant(),
              supervisor.getSupervisorConsultant(),
              supervisor.getSupervisorConsultant().getMatrixUserId(),
              session.getMatrixRoomId());
      supervisor.setMatrixRoomId(sideRoomId);
      supervisor.setIsActive(true);
      supervisor.setNotes(
          SupervisionNotes.encode(reason, payload.justification, SupervisionConsent.APPROVED));
      log.info(
          "Supervision consent APPROVED — activated supervisor {} for session {}",
          supervisor.getSupervisorConsultant().getUsername(),
          sessionId);
    } else {
      supervisor.setIsActive(false);
      supervisor.setNotes(
          SupervisionNotes.encode(reason, payload.justification, SupervisionConsent.DECLINED));
      log.info(
          "Supervision consent DECLINED for supervisor {} on session {}",
          supervisor.getSupervisorConsultant().getUsername(),
          sessionId);
    }
    return sessionSupervisorRepository.save(supervisor);
  }

  /**
   * The supervision requests for a session that are still awaiting the ratsuchende's consent
   * (ADR-008 item 4). Consent state is decoded from the {@code notes} JSON, not a column.
   *
   * @param sessionId the session ID
   * @return the pending-consent supervisor rows
   */
  public List<SessionSupervisor> getPendingConsentSupervisors(Long sessionId) {
    return sessionSupervisorRepository.findBySessionId(sessionId).stream()
        .filter(SessionSupervisorFacade::isPendingConsent)
        .collect(java.util.stream.Collectors.toList());
  }

  /** True when the row is an inactive, not-removed request whose recorded consent is PENDING. */
  private static boolean isPendingConsent(SessionSupervisor supervisor) {
    return Boolean.FALSE.equals(supervisor.getIsActive())
        && supervisor.getRemovedDate() == null
        && SupervisionConsent.PENDING
            .name()
            .equals(SupervisionNotes.decode(supervisor.getNotes()).consent);
  }

  /**
   * Remove a supervisor from a session.
   *
   * @param sessionId the session ID
   * @param supervisorId the supervisor ID to remove
   * @param removedByConsultant the consultant removing the supervisor
   */
  @Transactional
  public void removeSupervisor(Long sessionId, Long supervisorId, Consultant removedByConsultant) {
    // Get session
    Session session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new NotFoundException("Session not found: " + sessionId));

    // Verify removedByConsultant has permission
    if (!hasPermissionToManageSupervisors(session, removedByConsultant)) {
      throw new ForbiddenException(
          "Consultant does not have permission to remove supervisors from this session");
    }

    // Get supervisor
    SessionSupervisor supervisor =
        sessionSupervisorRepository
            .findById(supervisorId)
            .orElseThrow(() -> new NotFoundException("Supervisor not found: " + supervisorId));

    if (!supervisor.getSession().getId().equals(sessionId)) {
      throw new BadRequestException("Supervisor does not belong to this session");
    }

    if (!supervisor.getIsActive()) {
      throw new BadRequestException("Supervisor is already removed");
    }

    // Remove the supervisor from BOTH rooms: the supervision side room (stored in
    // supervisor.matrixRoomId, ADR-008) and the client room they observed
    // (session.matrixRoomId).
    String sideRoomId = supervisor.getMatrixRoomId();
    String clientRoomId = session.getMatrixRoomId();
    String supervisorMatrixUserId = supervisor.getSupervisorConsultant().getMatrixUserId();
    if (supervisorMatrixUserId != null
        && !supervisorMatrixUserId.isEmpty()
        && removedByConsultant.getMatrixUserId() != null) {
      String consultantToken =
          matrixSynapseService.loginAsUserAccessToken(removedByConsultant.getMatrixUserId());
      if (consultantToken != null) {
        removeFromRoomIfPresent(
            sideRoomId, supervisorMatrixUserId, consultantToken, supervisor, "supervision side");
        removeFromRoomIfPresent(
            clientRoomId, supervisorMatrixUserId, consultantToken, supervisor, "client");
      }
    }

    // Deactivate supervisor
    supervisor.setIsActive(false);
    supervisor.setRemovedDate(LocalDateTime.now());
    sessionSupervisorRepository.save(supervisor);

    log.info(
        "Removed supervisor {} from session {} by consultant {}",
        supervisor.getSupervisorConsultant().getUsername(),
        sessionId,
        removedByConsultant.getUsername());
  }

  /**
   * Get all active supervisors for a session.
   *
   * @param sessionId the session ID
   * @return list of active supervisors
   */
  public List<SessionSupervisor> getSupervisors(Long sessionId) {
    return sessionSupervisorRepository.findBySessionIdAndIsActiveTrue(sessionId);
  }

  /**
   * ADR-008: ensure a supervision side room exists for the session and the supervisor is a member
   * of it. One side room is shared by all supervisors of a session. The client is NEVER invited, so
   * asides sent here are never delivered to the client's Matrix device. The room is created with
   * the {@code private_chat} preset (join_rule = invite), so the client cannot join even if the id
   * leaks.
   *
   * @return the side room id (existing, reused, or newly created)
   */
  private String ensureSupervisionSideRoom(
      Session session,
      Consultant addedByConsultant,
      Consultant supervisorConsultant,
      String supervisorMatrixUserId,
      String consultantToken) {

    String clientRoomId = session.getMatrixRoomId();

    // Reuse an existing side room if another active supervisor already has one for
    // this session.
    // Old-style rows stored the CLIENT room id in matrixRoomId — never reuse that
    // as a side room.
    String sideRoomId =
        sessionSupervisorRepository.findBySessionIdAndIsActiveTrue(session.getId()).stream()
            .map(SessionSupervisor::getMatrixRoomId)
            .filter(id -> id != null && !id.isEmpty() && !id.equals(clientRoomId))
            .findFirst()
            .orElse(null);

    if (sideRoomId == null) {
      // Create the side room as the acting consultant (they become an owner/member).
      String alias = "supervision_" + session.getId() + "_" + System.nanoTime();
      try {
        var response =
            matrixSynapseService.createRoom(
                "Supervision – Session " + session.getId(), alias, consultantToken);
        sideRoomId =
            response != null && response.getBody() != null ? response.getBody().getRoomId() : null;
      } catch (Exception e) {
        throw new InternalServerErrorException(
            "Failed to create supervision side room: " + e.getMessage());
      }
      if (sideRoomId == null || sideRoomId.isEmpty()) {
        throw new InternalServerErrorException(
            "Supervision side room creation returned no room id");
      }
      log.info("Created supervision side room {} for session {}", sideRoomId, session.getId());

      // Ensure the assigned consultant (who handles the case) is also in the side
      // room, in case a
      // different same-agency consultant created it.
      Consultant assigned = session.getConsultant();
      if (assigned != null
          && assigned.getMatrixUserId() != null
          && !assigned.getId().equals(addedByConsultant.getId())) {
        inviteAndJoin(sideRoomId, assigned.getMatrixUserId(), consultantToken);
      }
    }

    // Invite + join the supervisor into the side room (full member — this is their
    // feedback
    // back-channel, not a read-only observation room).
    inviteAndJoin(sideRoomId, supervisorMatrixUserId, consultantToken);
    log.info(
        "Supervisor {} added to supervision side room {} for session {}",
        supervisorConsultant.getUsername(),
        sideRoomId,
        session.getId());
    return sideRoomId;
  }

  /** Invite a Matrix user to a room and accept the invite on their behalf. */
  private void inviteAndJoin(String roomId, String matrixUserId, String inviterToken) {
    try {
      matrixSynapseService.inviteUserToRoom(roomId, matrixUserId, inviterToken);
    } catch (MatrixInviteUserException e) {
      if (e.getMessage() != null && e.getMessage().contains("already in the room")) {
        log.info("User {} already in room {}, skipping invite", matrixUserId, roomId);
      } else {
        throw new InternalServerErrorException(
            "Failed to invite user to supervision side room: " + e.getMessage());
      }
    } catch (Exception e) {
      throw new InternalServerErrorException(
          "Failed to invite user to supervision side room: " + e.getMessage());
    }
    String userToken = matrixSynapseService.loginAsUserAccessToken(matrixUserId);
    if (userToken == null) {
      throw new InternalServerErrorException("Failed to create user Matrix token");
    }
    matrixSynapseService.joinRoom(roomId, userToken);
  }

  private void inviteSupervisorToClientRoom(
      String matrixRoomId,
      String supervisorMatrixUserId,
      String consultantToken,
      Consultant supervisorConsultant,
      Session session) {
    try {
      matrixSynapseService.inviteUserToRoom(matrixRoomId, supervisorMatrixUserId, consultantToken);
      log.info(
          "Invited supervisor {} to Matrix room {} for session {}",
          supervisorConsultant.getUsername(),
          matrixRoomId,
          session.getId());
    } catch (MatrixInviteUserException e) {
      if (e.getMessage() != null && e.getMessage().contains("already in the room")) {
        log.info(
            "Supervisor {} already in Matrix room {} for session {}, continuing",
            supervisorConsultant.getUsername(),
            matrixRoomId,
            session.getId());
        return;
      }
      log.error(
          "Failed to invite supervisor {} to Matrix room {}: {}",
          supervisorConsultant.getUsername(),
          matrixRoomId,
          e.getMessage());
      throw new InternalServerErrorException(
          "Failed to invite supervisor to Matrix room: " + e.getMessage());
    } catch (Exception e) {
      log.error(
          "Failed to invite supervisor {} to Matrix room {}: {}",
          supervisorConsultant.getUsername(),
          matrixRoomId,
          e.getMessage());
      throw new InternalServerErrorException(
          "Failed to invite supervisor to Matrix room: " + e.getMessage());
    }
  }

  /** Remove a user from a room if a room id is present; log but never fail the removal flow. */
  private void removeFromRoomIfPresent(
      String roomId,
      String supervisorMatrixUserId,
      String consultantToken,
      SessionSupervisor supervisor,
      String roomLabel) {
    if (roomId == null || roomId.isEmpty()) {
      return;
    }
    boolean removed =
        matrixSynapseService.removeUserFromRoom(roomId, supervisorMatrixUserId, consultantToken);
    if (removed) {
      log.info(
          "Removed supervisor {} from {} room {}",
          supervisor.getSupervisorConsultant().getUsername(),
          roomLabel,
          roomId);
    } else {
      log.warn(
          "Failed to remove supervisor {} from {} room {}, but continuing",
          supervisor.getSupervisorConsultant().getUsername(),
          roomLabel,
          roomId);
    }
  }

  /**
   * Check if consultant has permission to manage supervisors for a session.
   *
   * @param session the session
   * @param consultant the consultant
   * @return true if has permission
   */
  private boolean hasPermissionToManageSupervisors(Session session, Consultant consultant) {
    boolean isAssignedConsultant =
        session.getConsultant() != null
            && session.getConsultant().getId().equals(consultant.getId());
    if (isAssignedConsultant) {
      log.info(
          "Consultant {} is the assigned consultant for session {}",
          consultant.getId(),
          session.getId());
      return true;
    }

    boolean sameAgency = areFromSameAgency(session, consultant);

    // ADR-008 item 4 (Frank, 2026-07-04): a Berater-Admin (agency admin) may manage supervisors for
    // sessions of their OWN agency — this is exactly their role. It holds even when the
    // assigned-only
    // tightening below is on, so agency admins are never locked out of their own agency's sessions.
    boolean isAgencyAdmin =
        authenticatedUser != null
            && (authenticatedUser.isRestrictedAgencyAdmin()
                || authenticatedUser.isAgencySuperAdmin());
    if (isAgencyAdmin && sameAgency) {
      log.info(
          "Consultant {} is an agency admin for session {} agency {} — allowing supervisor management",
          consultant.getId(),
          session.getId(),
          session.getAgencyId());
      return true;
    }

    // ADR-008 item 4 tightening — the agency-admin Admin-board policy: when enabled, only the
    // assigned consultant and agency admins (above) may manage supervisors; a plain same-agency
    // consultant is denied. Defaults OFF, so at runtime nothing changes until it is turned on.
    if (restrictAddToAssignedConsultant) {
      log.info(
          "Consultant {} is neither the assigned consultant nor an agency admin for session {} and "
              + "the assigned-only restriction is enabled (ADR-008) — denying",
          consultant.getId(),
          session.getId());
      return false;
    }

    // Current default behaviour (flag off): assigned consultant OR any same-agency consultant.
    log.info(
        "Consultant {} same agency check for session {} (agencyId: {}): {}",
        consultant.getId(),
        session.getId(),
        session.getAgencyId(),
        sameAgency);
    return sameAgency;
  }

  /**
   * Check if session and consultant are from the same agency.
   *
   * @param session the session
   * @param consultant the consultant
   * @return true if same agency
   */
  private boolean areFromSameAgency(Session session, Consultant consultant) {
    if (session.getAgencyId() == null) {
      return false;
    }
    // Check if consultant has agency relationship
    // Note: getConsultantAgencies() might return null if not loaded, so handle that
    Set<ConsultantAgency> consultantAgencies = consultant.getConsultantAgencies();
    if (consultantAgencies == null || consultantAgencies.isEmpty()) {
      log.warn(
          "Consultant {} has no loaded ConsultantAgencies. Checking via repository.",
          consultant.getId());
      // Fallback: Check via repository if relationship not loaded
      List<ConsultantAgency> agencies =
          consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(consultant.getId());
      return agencies.stream().anyMatch(ca -> ca.getAgencyId().equals(session.getAgencyId()));
    }
    return consultantAgencies.stream()
        .anyMatch(ca -> ca.getAgencyId().equals(session.getAgencyId()));
  }

  /**
   * Get consultant from AuthenticatedUser.
   *
   * @param authenticatedUser the authenticated user
   * @return the consultant
   */
  private Consultant getConsultantFromAuthenticatedUser(
      de.caritas.cob.userservice.api.helper.AuthenticatedUser authenticatedUser) {
    return userAccountService.retrieveValidatedConsultant();
  }
}
