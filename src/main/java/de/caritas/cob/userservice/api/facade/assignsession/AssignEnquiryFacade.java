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
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.statistics.event.AssignSessionStatisticsEvent;
import static org.apache.commons.lang3.StringUtils.isBlank;
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
  private final @NonNull AgencyMatrixCredentialClient agencyMatrixCredentialClient;

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
        // MATRIX MIGRATION: Extract plain username from Matrix ID (consultant.getUsername() is
        // encrypted in DB)
        String consultantMatrixUsername = null;
        if (consultant.getMatrixUserId() != null && consultant.getMatrixUserId().startsWith("@")) {
          consultantMatrixUsername = consultant.getMatrixUserId().substring(1).split(":")[0];
        }

        // Use consultant credentials
        var consultantPassword = consultant.getMatrixPassword();
        if (consultantPassword == null) {
          log.warn(
              "Consultant {} has no Matrix password - cannot assign to Matrix room",
              consultant.getUsername());
          // Continue without Matrix room - fallback to RocketChat or no messaging
        }

        if (consultantPassword != null && consultantMatrixUsername != null) {
          String existingRoomId = session.getMatrixRoomId();

          if (existingRoomId != null && !existingRoomId.isBlank()) {
            // REUSE EXISTING ROOM (agency-user room)
            log.info(
                "Reusing existing Matrix room {} for session {} (agency-user room)",
                existingRoomId,
                session.getId());

            try {
              // Get agency service account credentials (agency created the room, so we need their token)
              var agencyCredentialsOpt = agencyMatrixCredentialClient.fetchMatrixCredentials(session.getAgencyId());
              
              if (agencyCredentialsOpt.isEmpty()) {
                log.warn(
                    "No agency Matrix credentials found for agency {}, falling back to create new room",
                    session.getAgencyId());
                createNewMatrixRoom(session, consultant, consultantMatrixUsername, consultantPassword);
                return;
              }

              var agencyCredentials = agencyCredentialsOpt.get();
              if (isBlank(agencyCredentials.getMatrixUserId()) || isBlank(agencyCredentials.getMatrixPassword())) {
                log.warn(
                    "Agency Matrix credentials incomplete for agency {}, falling back to create new room",
                    session.getAgencyId());
                createNewMatrixRoom(session, consultant, consultantMatrixUsername, consultantPassword);
                return;
              }

              // Extract agency Matrix username
              String agencyMatrixUsername = null;
              if (agencyCredentials.getMatrixUserId().startsWith("@")) {
                agencyMatrixUsername = agencyCredentials.getMatrixUserId().substring(1).split(":")[0];
              }

              if (isBlank(agencyMatrixUsername)) {
                log.warn("Invalid agency Matrix user ID, falling back to create new room");
                createNewMatrixRoom(session, consultant, consultantMatrixUsername, consultantPassword);
                return;
              }

              // Login as agency service account (room creator)
              String agencyToken = matrixSynapseService.loginUser(
                  agencyMatrixUsername, agencyCredentials.getMatrixPassword());

              if (isBlank(agencyToken)) {
                log.error(
                    "Failed to login agency service account for room reuse, falling back to create new room");
                createNewMatrixRoom(session, consultant, consultantMatrixUsername, consultantPassword);
                return;
              }

              // Use agency token to invite consultant to existing room
              try {
                matrixSynapseService.inviteUserToRoom(
                    existingRoomId, consultant.getMatrixUserId(), agencyToken);
                log.info(
                    "Agency invited consultant {} to existing room {}",
                    consultant.getUsername(),
                    existingRoomId);
              } catch (Exception e) {
                log.warn(
                    "Failed to invite consultant to room using agency token: {}", e.getMessage());
                // Continue anyway - might already be invited
              }

              // Use agency token to set consultant as admin (power level 100)
              boolean powerLevelSet =
                  matrixSynapseService.setUserPowerLevel(
                      existingRoomId, consultant.getMatrixUserId(), 100, agencyToken);
              if (powerLevelSet) {
                log.info(
                    "Set consultant {} as admin (power level 100) in room {} using agency token",
                    consultant.getUsername(),
                    existingRoomId);
              } else {
                log.warn(
                    "Failed to set power level for consultant {} in room {}",
                    consultant.getUsername(),
                    existingRoomId);
              }

              // Login consultant and join room
              String consultantToken =
                  matrixSynapseService.loginUser(consultantMatrixUsername, consultantPassword);

              if (consultantToken != null) {
                // Auto-join consultant to room
                boolean consultantJoined = matrixSynapseService.joinRoom(existingRoomId, consultantToken);
                if (consultantJoined) {
                  log.info(
                      "Consultant {} successfully joined existing room {} (all messages preserved)",
                      consultant.getUsername(),
                      existingRoomId);

                  // Remove agency service account from room (now only consultant + user remain)
                  boolean agencyRemoved =
                      matrixSynapseService.removeUserFromRoom(
                          existingRoomId, agencyCredentials.getMatrixUserId(), agencyToken);
                  if (agencyRemoved) {
                    log.info(
                        "Removed agency service account {} from room {} (only consultant + user remain)",
                        agencyCredentials.getMatrixUserId(),
                        existingRoomId);
                  } else {
                    log.warn(
                        "Failed to remove agency service account {} from room {}",
                        agencyCredentials.getMatrixUserId(),
                        existingRoomId);
                  }
                } else {
                  log.warn(
                      "Consultant {} failed to join existing room {}",
                      consultant.getUsername(),
                      existingRoomId);
                }
              } else {
                log.error("Failed to login consultant for room join");
              }

              // DON'T overwrite session.matrixRoomId - keep existing room ID
              // No need to save session since matrixRoomId hasn't changed
            } catch (Exception e) {
              log.error(
                  "Failed to reuse existing room {} for session {}, falling back to create new room: {}",
                  existingRoomId,
                  session.getId(),
                  e.getMessage(),
                  e);
              // Fall back to creating new room
              createNewMatrixRoom(session, consultant, consultantMatrixUsername, consultantPassword);
            }
          } else {
            // NO EXISTING ROOM - Create new room (backward compatibility)
            log.info(
                "No existing room found for session {}, creating new Matrix room", session.getId());
            createNewMatrixRoom(session, consultant, consultantMatrixUsername, consultantPassword);
          }
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

  /**
   * Creates a new Matrix room for consultant and user (fallback when no existing room).
   *
   * @param session the session
   * @param consultant the consultant
   * @param consultantMatrixUsername the consultant's Matrix username
   * @param consultantPassword the consultant's Matrix password
   */
  private void createNewMatrixRoom(
      Session session,
      Consultant consultant,
      String consultantMatrixUsername,
      String consultantPassword) {
    try {
      var roomName = "Session " + session.getId() + " - " + consultant.getUsername();
      var roomAlias = "session_" + session.getId();

      var matrixResponse =
          matrixSynapseService.createRoomAsConsultant(
              roomName, roomAlias, consultantMatrixUsername, consultantPassword);

      if (matrixResponse != null
          && matrixResponse.getBody() != null
          && matrixResponse.getBody().getRoomId() != null) {
        session.setMatrixRoomId(matrixResponse.getBody().getRoomId());
        sessionService.saveSession(session);

        // Invite user to room
        String consultantToken =
            matrixSynapseService.loginUser(consultantMatrixUsername, consultantPassword);

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
            "Successfully created new Matrix room: {} with ID: {} for session: {}",
            roomName,
            matrixResponse.getBody().getRoomId(),
            session.getId());
      }
    } catch (Exception e) {
      log.error(
          "Failed to create new Matrix room for session {}: {}", session.getId(), e.getMessage());
    }
  }
}