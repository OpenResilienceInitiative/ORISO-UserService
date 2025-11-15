package de.caritas.cob.userservice.api.facade.assignsession;

import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW;
import static java.util.Objects.nonNull;
import static java.util.concurrent.CompletableFuture.supplyAsync;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupMemberDTO;
import de.caritas.cob.userservice.api.admin.service.rocketchat.RocketChatRemoveFromGroupOperationService;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.facade.RocketChatFacade;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.statistics.event.AssignSessionStatisticsEvent;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.statisticsservice.generated.web.model.UserRole;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.servlet.http.HttpServletRequest;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * Facade to encapsulate the steps for accepting an enquiry and/or assigning a enquiry to a
 * consultant.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssignEnquiryFacade {

  private final @NonNull SessionService sessionService;
  private final @NonNull RocketChatFacade rocketChatFacade;
  private final @NonNull IdentityClient identityClient;
  private final @NonNull SessionToConsultantVerifier sessionToConsultantVerifier;
  private final @NonNull ConsultingTypeManager consultingTypeManager;
  private final @NonNull UnauthorizedMembersProvider unauthorizedMembersProvider;
  private final @NonNull StatisticsService statisticsService;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull HttpServletRequest httpServletRequest;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull ConsultantRepository consultantRepository;

  /**
   * Assigns the given {@link Session} session to the given {@link Consultant}. Remove all other
   * consultants from the Rocket.Chat group which don't have the right to view this session anymore.
   *
   * <p>If the statistics function is enabled, the assignment of the enquired is processed as
   * statistical event.
   *
   * @param session the session to assign the consultant
   * @param consultant the consultant to assign
   */
  public void assignRegisteredEnquiry(Session session, Consultant consultant) {
    assignRegisteredEnquiry(session, consultant, false);
  }

  public void assignRegisteredEnquiry(
      Session session,
      Consultant consultant,
      boolean skipConsultantAssignmentAndSessionInProgressCheck) {
    var requestURI = httpServletRequest.getRequestURI();
    var requestReferer = httpServletRequest.getHeader(HttpHeaders.REFERER);
    assignEnquiry(session, consultant, skipConsultantAssignmentAndSessionInProgressCheck);
    supplyAsync(updateRocketChatRooms(session, consultant, TenantContext.getCurrentTenant()))
        .thenRun(
            () -> {
              var event =
                  new AssignSessionStatisticsEvent(
                      consultant.getId(), UserRole.CONSULTANT, session.getId());
              event.setRequestUri(requestURI);
              event.setRequestReferer(requestReferer);
              event.setRequestUserId(consultant.getId());

              statisticsService.fireEvent(event);
            });
  }

  /**
   * Assigns the given {@link Session} session to the given {@link Consultant}. Add the given {@link
   * Consultant} to the Rocket.Chat group.
   *
   * @param session the session to assign the consultant
   * @param consultant the consultant to assign
   */
  public void assignAnonymousEnquiry(Session session, Consultant consultant) {
    assignEnquiry(session, consultant);
    try {
      this.rocketChatFacade.addUserToRocketChatGroup(
          consultant.getRocketChatId(), session.getGroupId());
      this.rocketChatFacade.removeSystemMessagesFromRocketChatGroup(session.getGroupId());
    } catch (Exception e) {
      rollbackSessionUpdate(session);
      throw new InternalServerErrorException(
          String.format(
              "Could not add consultant %s to group %s",
              consultant.getRocketChatId(), session.getGroupId()));
    }
  }

  private void assignEnquiry(Session session, Consultant consultant) {
    assignEnquiry(session, consultant, false);
  }

  private void assignEnquiry(
      Session session,
      Consultant consultant,
      boolean skipConsultantAssignmentAndSessionInProgressChecks) {
    var consultantSessionDTO =
        ConsultantSessionDTO.builder().consultant(consultant).session(session).build();
    if (!skipConsultantAssignmentAndSessionInProgressChecks) {
      sessionToConsultantVerifier.verifySessionIsNotInProgress(consultantSessionDTO);
    }
    sessionToConsultantVerifier.verifyPreconditionsForAssignment(
        consultantSessionDTO, skipConsultantAssignmentAndSessionInProgressChecks);

    sessionService.updateConsultantAndStatusForSession(session, consultant, IN_PROGRESS);

    // Create Matrix room and invite user
    try {
      // First, ensure consultant has a Matrix account
      if (consultant.getMatrixUserId() == null) {
        try {
          var consultantPassword = "@Consultant123"; // TODO: Get from secure storage
          var matrixUserResponse =
              matrixSynapseService.createUser(
                  consultant.getUsername(),
                  consultantPassword,
                  consultant.getFirstName() + " " + consultant.getLastName());

          if (matrixUserResponse.getBody() != null
              && matrixUserResponse.getBody().getUserId() != null) {
            consultant.setMatrixUserId(matrixUserResponse.getBody().getUserId());
            consultantRepository.save(consultant);
            log.info(
                "Created Matrix account for consultant: {} with ID: {}",
                consultant.getUsername(),
                matrixUserResponse.getBody().getUserId());
          }
        } catch (Exception e) {
          log.error(
              "Failed to create Matrix account for consultant: {}", consultant.getUsername(), e);
        }
      }

      log.info(
          "Matrix account status for session {}: user_matrix_id={}, consultant_matrix_id={}",
          session.getId(),
          session.getUser().getMatrixUserId(),
          consultant.getMatrixUserId());

      if (session.getUser().getMatrixUserId() != null && consultant.getMatrixUserId() != null) {
        var roomName = "Session " + session.getId() + " - " + consultant.getUsername();
        var roomAlias = "session_" + session.getId();

        // MATRIX MIGRATION: Extract plain username from Matrix ID (consultant.getUsername() is
        // encrypted in DB)
        String consultantMatrixUsername = null;
        if (consultant.getMatrixUserId() != null && consultant.getMatrixUserId().startsWith("@")) {
          consultantMatrixUsername = consultant.getMatrixUserId().substring(1).split(":")[0];
        }

        // Use consultant credentials to create room (consultant will be room admin)
        var consultantPassword = consultant.getMatrixPassword();
        if (consultantPassword == null) {
          log.warn(
              "Consultant {} has no Matrix password - cannot create Matrix room",
              consultant.getUsername());
          // Continue without Matrix room - fallback to RocketChat or no messaging
        }

        var matrixResponse =
            consultantPassword != null
                ? matrixSynapseService.createRoomAsConsultant(
                    roomName, roomAlias, consultantMatrixUsername, consultantPassword)
                : null;

        if (matrixResponse != null
            && matrixResponse.getBody() != null
            && matrixResponse.getBody().getRoomId() != null) {
          session.setMatrixRoomId(matrixResponse.getBody().getRoomId());
          sessionService.saveSession(session);

          // Invite user to room - reuse consultantMatrixUsername from above
          String consultantToken = null;
          if (consultantMatrixUsername != null) {
            consultantToken =
                matrixSynapseService.loginUser(consultantMatrixUsername, consultantPassword);
          }

          if (consultantToken != null) {
            String roomId = matrixResponse.getBody().getRoomId();

            // Invite the user to the room
            matrixSynapseService.inviteUserToRoom(
                roomId, session.getUser().getMatrixUserId(), consultantToken);

            // Auto-accept invitation: Login as user and join the room
            String userPassword = session.getUser().getMatrixPassword();

            // Extract Matrix username from matrix_user_id (handles encrypted DB usernames)
            String userMatrixUsername = null;
            if (session.getUser().getMatrixUserId() != null
                && session.getUser().getMatrixUserId().startsWith("@")) {
              userMatrixUsername = session.getUser().getMatrixUserId().substring(1).split(":")[0];
            }

            if (userMatrixUsername != null) {
              String userToken = matrixSynapseService.loginUser(userMatrixUsername, userPassword);
              if (userToken != null) {
                boolean userJoined = matrixSynapseService.joinRoom(roomId, userToken);
                if (userJoined) {
                  log.info(
                      "User {} (Matrix: {}) auto-accepted room invitation for room: {}",
                      session.getUser().getUsername(),
                      userMatrixUsername,
                      roomId);
                } else {
                  log.warn(
                      "User {} (Matrix: {}) failed to auto-accept room invitation for room: {}",
                      session.getUser().getUsername(),
                      userMatrixUsername,
                      roomId);
                }
              }
            } else {
              log.warn(
                  "User {} has invalid matrix_user_id, skipping auto-join",
                  session.getUser().getUsername());
            }

            // Consultant auto-joins (as room creator, consultant is already in room, but let's
            // ensure)
            boolean consultantJoined = matrixSynapseService.joinRoom(roomId, consultantToken);
            if (consultantJoined) {
              log.info("Consultant {} confirmed in room: {}", consultant.getUsername(), roomId);
            }
          }

          log.info(
              "Successfully created Matrix room: {} with ID: {} for session: {}",
              roomName,
              matrixResponse.getBody().getRoomId(),
              session.getId());
        }
      } else {
        log.warn(
            "User does not have Matrix user ID, skipping room creation for session: {}",
            session.getId());
      }
    } catch (Exception e) {
      log.error(
          "Matrix room creation failed for session: {}, but continuing with assignment",
          session.getId(),
          e);
    }
  }

  private Supplier<Object> updateRocketChatRooms(
      Session session, Consultant consultant, Long currentTenantId) {
    return () -> {
      tenantContextProvider.setCurrentTenantContextIfMissing(currentTenantId);
      updateRocketChatRooms(session.getGroupId(), session, consultant);
      return null;
    };
  }

  public void updateRocketChatRooms(String rcGroupId, Session session, Consultant consultant) {
    try {
      var memberList = this.rocketChatFacade.retrieveRocketChatMembers(rcGroupId);
      removeUnauthorizedMembers(rcGroupId, session, consultant, memberList);
      this.rocketChatFacade.removeSystemMessagesFromRocketChatGroup(rcGroupId);

    } catch (Exception e) {
      LogService.logRocketChatError(e);
      throw e;
    }
  }

  private void removeUnauthorizedMembers(
      String rcGroupId, Session session, Consultant consultant, List<GroupMemberDTO> memberList) {
    var consultantsToRemoveFromRocketChat =
        unauthorizedMembersProvider.obtainConsultantsToRemove(
            rcGroupId, session, consultant, memberList);

    var rocketChatRemoveFromGroupOperationService =
        RocketChatRemoveFromGroupOperationService.getInstance(
                this.rocketChatFacade, this.identityClient, this.consultingTypeManager)
            .onSessionConsultants(Map.of(session, consultantsToRemoveFromRocketChat));

    if (rcGroupId.equalsIgnoreCase(session.getGroupId())) {
      rocketChatRemoveFromGroupOperationService.removeFromGroupAndIgnoreGroupNotFound();
    }
  }

  private void rollbackSessionUpdate(Session session) {
    if (nonNull(session)) {
      sessionService.updateConsultantAndStatusForSession(session, null, NEW);
    }
  }
}
