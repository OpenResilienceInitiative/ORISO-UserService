package de.caritas.cob.userservice.api.facade;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

  private static final int SUPERVISOR_POWER_LEVEL = 10; // Read-only observer level

  /**
   * Add a supervisor to a session. The supervisor must be from the same agency as the session.
   *
   * @param sessionId the session ID
   * @param supervisorConsultantId the consultant ID to add as supervisor
   * @param addedByConsultant the consultant adding the supervisor
   * @param notes optional notes/reason for supervision
   * @return the created SessionSupervisor
   */
  @Transactional
  public SessionSupervisor addSupervisor(
      Long sessionId, String supervisorConsultantId, Consultant addedByConsultant, String notes) {
    // Get session
    Session session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new NotFoundException("Session not found: " + sessionId));

    // Verify addedByConsultant has permission (must be assigned consultant or from same agency)
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
    log.info("Permission granted for consultant {} to add supervisor to session {}", addedByConsultant.getId(), sessionId);

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
      throw new BadRequestException(
          "Supervisor must be from the same agency as the session");
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

    // Get addedByConsultant's Matrix credentials for room operations
    String addedByConsultantMatrixUsername = null;
    if (addedByConsultant.getMatrixUserId() != null
        && addedByConsultant.getMatrixUserId().startsWith("@")) {
      addedByConsultantMatrixUsername =
          addedByConsultant.getMatrixUserId().substring(1).split(":")[0];
    }

    String addedByConsultantPassword = addedByConsultant.getMatrixPassword();
    if (addedByConsultantPassword == null) {
      throw new InternalServerErrorException(
          "Consultant adding supervisor does not have Matrix credentials");
    }

    // Login as addedByConsultant to get token
    String consultantToken =
        matrixSynapseService.loginUser(addedByConsultantMatrixUsername, addedByConsultantPassword);
    if (consultantToken == null) {
      throw new InternalServerErrorException("Failed to login consultant for Matrix operations");
    }

    // Invite supervisor to Matrix room
    try {
      matrixSynapseService.inviteUserToRoom(matrixRoomId, supervisorMatrixUserId, consultantToken);
      log.info(
          "Invited supervisor {} to Matrix room {} for session {}",
          supervisorConsultant.getUsername(),
          matrixRoomId,
          sessionId);
    } catch (Exception e) {
      log.error(
          "Failed to invite supervisor {} to Matrix room {}: {}",
          supervisorConsultant.getUsername(),
          matrixRoomId,
          e.getMessage());
      throw new InternalServerErrorException(
          "Failed to invite supervisor to Matrix room: " + e.getMessage());
    }

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

    // Login supervisor and join room
    String supervisorMatrixUsername = null;
    if (supervisorMatrixUserId.startsWith("@")) {
      supervisorMatrixUsername = supervisorMatrixUserId.substring(1).split(":")[0];
    }
    String supervisorPassword = supervisorConsultant.getMatrixPassword();
    if (supervisorPassword != null) {
      String supervisorToken =
          matrixSynapseService.loginUser(supervisorMatrixUsername, supervisorPassword);
      if (supervisorToken != null) {
        boolean joined = matrixSynapseService.joinRoom(matrixRoomId, supervisorToken);
        if (joined) {
          log.info(
              "Supervisor {} successfully joined Matrix room {}",
              supervisorConsultant.getUsername(),
              matrixRoomId);
        }
      }
    }

    // Create SessionSupervisor entity
    SessionSupervisor sessionSupervisor =
        SessionSupervisor.builder()
            .session(session)
            .supervisorConsultant(supervisorConsultant)
            .addedByConsultant(addedByConsultant)
            .addedDate(LocalDateTime.now())
            .isActive(true)
            .matrixRoomId(matrixRoomId)
            .notes(notes)
            .build();

    SessionSupervisor saved = sessionSupervisorRepository.save(sessionSupervisor);
    log.info(
        "Added supervisor {} to session {} by consultant {}",
        supervisorConsultant.getUsername(),
        sessionId,
        addedByConsultant.getUsername());

    return saved;
  }

  /**
   * Remove a supervisor from a session.
   *
   * @param sessionId the session ID
   * @param supervisorId the supervisor ID to remove
   * @param removedByConsultant the consultant removing the supervisor
   */
  @Transactional
  public void removeSupervisor(
      Long sessionId, Long supervisorId, Consultant removedByConsultant) {
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

    // Remove from Matrix room
    String matrixRoomId = supervisor.getMatrixRoomId();
    if (matrixRoomId != null && !matrixRoomId.isEmpty()) {
      String supervisorMatrixUserId =
          supervisor.getSupervisorConsultant().getMatrixUserId();
      if (supervisorMatrixUserId != null && !supervisorMatrixUserId.isEmpty()) {
        // Get removedByConsultant's Matrix credentials
        String removedByConsultantMatrixUsername = null;
        if (removedByConsultant.getMatrixUserId() != null
            && removedByConsultant.getMatrixUserId().startsWith("@")) {
          removedByConsultantMatrixUsername =
              removedByConsultant.getMatrixUserId().substring(1).split(":")[0];
        }

        String removedByConsultantPassword = removedByConsultant.getMatrixPassword();
        if (removedByConsultantPassword != null) {
          String consultantToken =
              matrixSynapseService.loginUser(
                  removedByConsultantMatrixUsername, removedByConsultantPassword);
          if (consultantToken != null) {
            boolean removed =
                matrixSynapseService.removeUserFromRoom(
                    matrixRoomId, supervisorMatrixUserId, consultantToken);
            if (removed) {
              log.info(
                  "Removed supervisor {} from Matrix room {}",
                  supervisor.getSupervisorConsultant().getUsername(),
                  matrixRoomId);
            } else {
              log.warn(
                  "Failed to remove supervisor {} from Matrix room {}, but continuing",
                  supervisor.getSupervisorConsultant().getUsername(),
                  matrixRoomId);
            }
          }
        }
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
   * Check if consultant has permission to manage supervisors for a session.
   *
   * @param session the session
   * @param consultant the consultant
   * @return true if has permission
   */
  private boolean hasPermissionToManageSupervisors(Session session, Consultant consultant) {
    // Must be the assigned consultant or from the same agency
    if (session.getConsultant() != null
        && session.getConsultant().getId().equals(consultant.getId())) {
      log.info("Consultant {} is the assigned consultant for session {}", consultant.getId(), session.getId());
      return true;
    }
    boolean sameAgency = areFromSameAgency(session, consultant);
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
      return agencies.stream()
          .anyMatch(ca -> ca.getAgencyId().equals(session.getAgencyId()));
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

