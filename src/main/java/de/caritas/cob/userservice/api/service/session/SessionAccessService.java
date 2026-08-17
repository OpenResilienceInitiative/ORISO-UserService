package de.caritas.cob.userservice.api.service.session;

import static java.util.Collections.emptySet;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Owns session lookup and authorization policy for askers and consultants. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionAccessService {

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull ConsultantTopicRepository consultantTopicRepository;
  private final @NonNull AgencyService agencyService;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull SessionSupervisorRepository sessionSupervisorRepository;

  /**
   * Returns the session only if the authenticated user is allowed to access it.
   *
   * @param sessionId the session ID
   * @param authenticatedUser the authenticated caller
   * @return the authorized {@link Session}
   */
  public Session assertUserHasAccess(Long sessionId, AuthenticatedUser authenticatedUser) {
    var session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new NotFoundException("Session with id %s not found.", sessionId));
    var roles = Optional.ofNullable(authenticatedUser.getRoles()).orElse(emptySet());

    checkUserPermissionForSession(session, authenticatedUser.getUserId(), roles);
    return session;
  }

  public Session getSessionByMatrixRoomIdAndUser(
      String matrixRoomId, String userId, Set<String> roles) {
    var session =
        sessionRepository
            .findByMatrixRoomId(matrixRoomId)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "Session with Matrix room ID %s not found.", matrixRoomId));
    checkUserPermissionForSession(session, userId, roles);
    return session;
  }

  void checkForUserOrConsultantRole(Set<String> roles) {
    if (!roles.contains(UserRole.USER.getValue())
        && !roles.contains(UserRole.CONSULTANT.getValue())) {
      throw new ForbiddenException(
          "No user or consultant role to retrieve sessions", LogService::logForbidden);
    }
  }

  void checkForAskerRoles(Set<String> roles) {
    if (!roles.contains(UserRole.USER.getValue())
        && !roles.contains(UserRole.ANONYMOUS.getValue())
        && !roles.contains(UserRole.CONSULTANT.getValue())) {
      throw new ForbiddenException(
          "No user or consultant role to retrieve sessions", LogService::logForbidden);
    }
  }

  /**
   * A session without a user falls through to the 403 rather than throwing NPE: callers such as
   * {@code UserSessionQueryService#getSessionsByUserAndRoomIds} feed every row matched by room id
   * into this check, and {@code Session#getUser()} is nullable (see {@code
   * FinishAnonymousConversationFacade#verifyPermissionToFinish}). The role grouping is
   * parenthesised explicitly — an authorization rule should not depend on operator precedence to be
   * read correctly.
   */
  void checkAskerPermissionForSession(Session session, String userId, Set<String> roles) {
    if (nonNull(session.getUser())) {
      boolean askerRoleMatches =
          roles.contains(UserRole.USER.getValue())
              || (session.getRegistrationType() == RegistrationType.ANONYMOUS
                  && roles.contains(UserRole.ANONYMOUS.getValue()));
      if (askerRoleMatches && userId.equals(session.getUser().getUserId())) {
        return;
      }
    }
    throw new ForbiddenException(
        String.format("Asker %s not allowed to access session with ID %s", userId, session.getId()),
        LogService::logForbidden);
  }

  boolean isConsultantPermittedToSession(Consultant consultant, Session session) {
    try {
      checkConsultantAssignment(consultant, session);
    } catch (ForbiddenException e) {
      // Only the session id, at debug: the exception message carries the consultant and session
      // identifiers, and this is an expected negative result on a filtering path — logging it at
      // INFO put identifiers into application logs on every non-permitted session.
      log.debug("Consultant not permitted to session {}", session.getId());
      return false;
    }
    return true;
  }

  void checkPermissionForConsultantSession(Session session, Consultant consultant) {
    if (!session.isAdvisedBy(consultant)
        && !isSupervisor(consultant, session)
        && !(session.isTeamSession() && consultant.isInAgency(session.getAgencyId()))
        && !isAllowedToAdviseByTopic(consultant, session)) {
      throw new ForbiddenException(
          String.format(
              "No permission for session %s by consultant %s",
              session.getId(), consultant.getId()));
    }
  }

  /** Returns true for invite-link / live-chat style registrations stored as REGISTERED. */
  public boolean isAnonymousStyleRegistration(Session session) {
    if (isNull(session)) {
      return false;
    }

    if ("00000".equals(session.getPostcode())) {
      return true;
    }

    if (nonNull(session.getUser()) && nonNull(session.getUser().getUsername())) {
      return session.getUser().getUsername().startsWith("Anonymous-");
    }

    return false;
  }

  private void checkUserPermissionForSession(Session session, String userId, Set<String> roles) {
    checkForUserOrConsultantRole(roles);
    checkIfUserAndNotOwnerOfSession(session, userId, roles);
    checkIfConsultantAndNotAssignedToSessionOrAgency(session, userId, roles);
  }

  private void checkIfUserAndNotOwnerOfSession(Session session, String userId, Set<String> roles) {
    if (roles.contains(UserRole.USER.getValue()) && !session.getUser().getUserId().equals(userId)) {
      throw new ForbiddenException(
          String.format("User %s has no permission to access session %s", userId, session.getId()),
          LogService::logForbidden);
    }
  }

  private void checkIfConsultantAndNotAssignedToSessionOrAgency(
      Session session, String userId, Set<String> roles) {
    if (roles.contains(UserRole.CONSULTANT.getValue())) {
      var consultant = loadConsultantOrThrow(userId);
      checkPermissionForConsultantSession(session, consultant);
    }
  }

  private void checkConsultantAssignment(Consultant consultant, Session session) {
    if (session.isAdvisedBy(consultant)
        || isSupervisor(consultant, session)
        || isAllowedToAdvise(consultant, session)
        || isAllowedToAdviseByTopic(consultant, session)
        || isAnonymousEnquiryAndAllowedToAdviseConsultingType(consultant, session)) {
      return;
    }
    throw new ForbiddenException(
        String.format(
            "No permission for session %s by consultant %s", session.getId(), consultant.getId()));
  }

  private boolean isSupervisor(Consultant consultant, Session session) {
    return sessionSupervisorRepository
        .findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(
            session.getId(), consultant.getId())
        .isPresent();
  }

  private boolean isAllowedToAdvise(Consultant consultant, Session session) {
    return isTeamSessionOrNew(session)
        && session.getAgencyId() != null
        && consultant.isInAgency(session.getAgencyId());
  }

  private boolean isAllowedToAdviseByTopic(Consultant consultant, Session session) {
    return isTeamSessionOrNew(session)
        && isAnonymousStyleRegistration(session)
        && nonNull(session.getMainTopicId())
        && consultantTopicRepository
            .findTopicIdsByConsultantId(consultant.getId())
            .contains(session.getMainTopicId());
  }

  private boolean isAnonymousEnquiryAndAllowedToAdviseConsultingType(
      Consultant consultant, Session session) {
    if (session.getStatus() != SessionStatus.NEW
        || session.getRegistrationType() != RegistrationType.ANONYMOUS) {
      return false;
    }
    var agencyIdsOfConsultant =
        consultant.getConsultantAgencies().stream()
            .map(ConsultantAgency::getAgencyId)
            .collect(Collectors.toList());
    var consultingTypes =
        agencyService.getAgencies(agencyIdsOfConsultant).stream()
            .map(AgencyDTO::getConsultingType)
            .collect(Collectors.toSet());
    return consultingTypes.contains(session.getConsultingTypeId());
  }

  private boolean isTeamSessionOrNew(Session session) {
    return session.isTeamSession() || SessionStatus.NEW == session.getStatus();
  }

  private Consultant loadConsultantOrThrow(String userId) {
    return consultantService.getConsultant(userId).orElseThrow(newBadRequestException(userId));
  }

  private Supplier<BadRequestException> newBadRequestException(String userId) {
    return () ->
        new BadRequestException(String.format("Consultant with id %s does not exist", userId));
  }
}
