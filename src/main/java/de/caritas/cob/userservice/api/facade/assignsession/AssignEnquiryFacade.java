package de.caritas.cob.userservice.api.facade.assignsession;

import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW;
import static java.util.Objects.nonNull;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupMemberDTO;
import de.caritas.cob.userservice.api.admin.service.rocketchat.RocketChatRemoveFromGroupOperationService;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.facade.EmailNotificationFacade;
import de.caritas.cob.userservice.api.facade.RocketChatFacade;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.liveevents.LiveEventNotificationService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.statistics.event.AssignSessionStatisticsEvent;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.statisticsservice.generated.web.model.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
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
  private final @NonNull EmailNotificationFacade emailNotificationFacade;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull HttpServletRequest httpServletRequest;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull MatrixConfig matrixConfig;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull UserRepository userRepository;
  private final @NonNull UserHelper userHelper;
  private final @NonNull UsernameTranscoder usernameTranscoder;
  private final @NonNull AgencyMatrixCredentialClient agencyMatrixCredentialClient;
  private final @NonNull LiveEventNotificationService liveEventNotificationService;
  private final @NonNull EventNotificationService eventNotificationService;

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
    liveEventNotificationService.sendAcceptAnonymousEnquiryEventToUser(
        session.getUser().getUserId());
    eventNotificationService.createInquiryAcceptedNotification(session, consultant);
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
      var user = session.getUser();
      ensureUserMatrixAccount(user);
      ensureConsultantMatrixAccount(consultant);

      log.info(
          "Matrix account status for session {}: user_matrix_id={}, consultant_matrix_id={}",
          session.getId(),
          user.getMatrixUserId(),
          consultant.getMatrixUserId());

      if (!isBlank(user.getMatrixUserId()) && !isBlank(consultant.getMatrixUserId())) {
        String existingRoomId = session.getMatrixRoomId();

        if (existingRoomId != null && !existingRoomId.isBlank()) {
          // REUSE EXISTING ROOM (agency-user room)
          log.info(
              "Reusing existing Matrix room {} for session {} (agency-user room)",
              existingRoomId,
              session.getId());

          try {
            // Get agency service account credentials (agency created the room, so we need their
            // token)
            var agencyCredentialsOpt =
                agencyMatrixCredentialClient.fetchMatrixCredentials(session.getAgencyId());

            if (agencyCredentialsOpt.isEmpty()) {
              log.warn(
                  "No agency Matrix credentials found for agency {}, falling back to create new room",
                  session.getAgencyId());
              createNewMatrixRoomOrFail(session, consultant);
              return;
            }

            var agencyCredentials = agencyCredentialsOpt.get();
            if (isBlank(agencyCredentials.getMatrixUserId())
                || isBlank(agencyCredentials.getMatrixPassword())) {
              log.warn(
                  "Agency Matrix credentials incomplete for agency {}, falling back to create new room",
                  session.getAgencyId());
              createNewMatrixRoomOrFail(session, consultant);
              return;
            }

            // Extract agency Matrix username
            String agencyMatrixUsername = null;
            if (agencyCredentials.getMatrixUserId().startsWith("@")) {
              agencyMatrixUsername = MatrixIds.localpart(agencyCredentials.getMatrixUserId());
            }

            if (isBlank(agencyMatrixUsername)) {
              log.warn("Invalid agency Matrix user ID, falling back to create new room");
              createNewMatrixRoomOrFail(session, consultant);
              return;
            }

            // Login as agency service account (room creator)
            String agencyToken =
                matrixSynapseService.loginUser(
                    agencyMatrixUsername, agencyCredentials.getMatrixPassword());

            if (isBlank(agencyToken)) {
              log.error(
                  "Failed to login agency service account for room reuse, falling back to create new room");
              createNewMatrixRoomOrFail(session, consultant);
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
                matrixSynapseService.loginAsUserAccessToken(consultant.getMatrixUserId());

            if (isBlank(consultantToken)) {
              log.warn(
                  "Failed to mint consultant Matrix token for room reuse on session {}, creating new room",
                  session.getId());
              createNewMatrixRoomOrFail(session, consultant);
              return;
            }

            // Auto-join consultant to room
            boolean consultantJoined =
                matrixSynapseService.joinRoom(existingRoomId, consultantToken);
            if (!consultantJoined) {
              log.warn(
                  "Consultant {} failed to join existing room {} for session {}, creating new room",
                  consultant.getUsername(),
                  existingRoomId,
                  session.getId());
              createNewMatrixRoomOrFail(session, consultant);
              return;
            }

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

            // DON'T overwrite session.matrixRoomId - keep existing room ID
            // No need to save session since matrixRoomId hasn't changed
          } catch (Exception e) {
            if (e instanceof InternalServerErrorException) {
              throw (InternalServerErrorException) e;
            }
            log.error(
                "Failed to reuse existing room {} for session {}, falling back to create new room: {}",
                existingRoomId,
                session.getId(),
                e.getMessage(),
                e);
            // Fall back to creating new room
            createNewMatrixRoomOrFail(session, consultant);
          }
        } else {
          // NO EXISTING ROOM - Create new room (backward compatibility)
          log.info(
              "No existing room found for session {}, creating new Matrix room", session.getId());
          createNewMatrixRoomOrFail(session, consultant);
        }
      } else {
        throw new InternalServerErrorException(
            String.format(
                "Matrix room creation failed for session %s: missing Matrix user id (user=%s, consultant=%s)",
                session.getId(), user.getMatrixUserId(), consultant.getMatrixUserId()));
      }
    } catch (Exception e) {
      rollbackSessionUpdate(session);
      log.error(
          "Matrix room creation failed for session: {}, rolling back assignment",
          session.getId(),
          e);
      if (e instanceof InternalServerErrorException) {
        throw (InternalServerErrorException) e;
      }
      throw new InternalServerErrorException(
          String.format("Matrix room creation failed for session %s", session.getId()), e);
    }

    emailNotificationFacade.sendInquiryAcceptedNotification(
        session.getUser(), consultant, TenantContext.getCurrentTenantData());
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
   */
  private void createNewMatrixRoomOrFail(Session session, Consultant consultant) {
    if (!createNewMatrixRoom(session, consultant)) {
      throw new InternalServerErrorException(
          String.format("Matrix room creation failed for session %s", session.getId()));
    }
  }

  private boolean createNewMatrixRoom(Session session, Consultant consultant) {
    String previousRoomId = session.getMatrixRoomId();
    try {
      var roomName = "Session " + session.getId() + " - " + consultant.getUsername();
      var roomAlias = buildUniqueSessionRoomAlias(session.getId(), consultant.getId());

      var matrixResponse =
          matrixSynapseService.createRoomAsMatrixUser(
              roomName, roomAlias, consultant.getMatrixUserId());

      if (matrixResponse == null
          || matrixResponse.getBody() == null
          || matrixResponse.getBody().getRoomId() == null) {
        log.error(
            "Matrix createRoom returned no room id for session {}, keeping previous room {}",
            session.getId(),
            previousRoomId);
        return false;
      }

      String roomId = matrixResponse.getBody().getRoomId();

      String consultantToken =
          matrixSynapseService.loginAsUserAccessToken(consultant.getMatrixUserId());

      if (isBlank(consultantToken)) {
        log.error(
            "Could not mint consultant Matrix token after creating room {} for session {}",
            roomId,
            session.getId());
        return false;
      }

      matrixSynapseService.inviteUserToRoom(
          roomId, session.getUser().getMatrixUserId(), consultantToken);

      String userToken =
          matrixSynapseService.loginAsUserAccessToken(session.getUser().getMatrixUserId());
      if (isBlank(userToken)) {
        log.error(
            "Could not mint user Matrix token after inviting user {} to room {}",
            session.getUser().getUsername(),
            roomId);
        return false;
      }

      boolean userJoined = matrixSynapseService.joinRoom(roomId, userToken);
      if (userJoined) {
        log.info(
            "User {} auto-accepted room invitation for room: {}",
            session.getUser().getUsername(),
            roomId);
      } else {
        log.warn(
            "User {} failed to auto-accept room invitation for room: {}",
            session.getUser().getUsername(),
            roomId);
        return false;
      }

      boolean consultantJoined = matrixSynapseService.joinRoom(roomId, consultantToken);
      if (consultantJoined) {
        log.info("Consultant {} confirmed in room: {}", consultant.getUsername(), roomId);
      } else {
        log.warn("Consultant {} failed to confirm room: {}", consultant.getUsername(), roomId);
        return false;
      }

      session.setMatrixRoomId(roomId);
      session.setGroupId(roomId);
      sessionService.saveSession(session);

      log.info(
          "Successfully created new Matrix room: {} with ID: {} for session: {}",
          roomName,
          roomId,
          session.getId());
      return true;
    } catch (Exception e) {
      log.error(
          "Failed to create new Matrix room for session {} (kept previous room {}): {}",
          session.getId(),
          previousRoomId,
          e.getMessage(),
          e);
      return false;
    }
  }

  private void ensureUserMatrixAccount(User user) {
    if (user == null || !isBlank(user.getMatrixUserId())) {
      return;
    }

    var matrixLocalpart = usernameTranscoder.decodeUsername(user.getUsername());
    var matrixUserId = createOrResolveMatrixUserId(matrixLocalpart, matrixLocalpart);
    if (!isBlank(matrixUserId)) {
      user.setMatrixUserId(matrixUserId);
      userRepository.save(user);
      log.info("Ensured Matrix account for user {} with ID: {}", user.getUserId(), matrixUserId);
    }
  }

  private void ensureConsultantMatrixAccount(Consultant consultant) {
    if (consultant == null || !isBlank(consultant.getMatrixUserId())) {
      return;
    }

    var matrixLocalpart = usernameTranscoder.decodeUsername(consultant.getUsername());
    var displayName = consultant.getFirstName() + " " + consultant.getLastName();
    var matrixUserId = createOrResolveMatrixUserId(matrixLocalpart, displayName);
    if (!isBlank(matrixUserId)) {
      consultant.setMatrixUserId(matrixUserId);
      consultantRepository.save(consultant);
      log.info(
          "Ensured Matrix account for consultant {} with ID: {}",
          consultant.getUsername(),
          matrixUserId);
    }
  }

  private String createOrResolveMatrixUserId(String matrixLocalpart, String displayName) {
    boolean tryFallback = false;
    try {
      var matrixResponse =
          matrixSynapseService.createUser(
              matrixLocalpart, userHelper.getRandomPassword(), displayName);
      if (matrixResponse.getBody() != null && matrixResponse.getBody().getUserId() != null) {
        return matrixResponse.getBody().getUserId();
      }
      // Body is null or userId missing — creation silently failed, do not attempt resolution
      log.warn(
          "Matrix user creation returned no userId for localpart {}, skipping fallback",
          matrixLocalpart);
      return null;
    } catch (de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException e) {
      // The server rejected the registration, most likely because the account already exists.
      // Try to verify and reuse it via a login probe.
      log.warn(
          "Failed to create Matrix user {} (may already exist), attempting to resolve: {}",
          matrixLocalpart,
          e.getMessage());
      tryFallback = true;
    } catch (Exception e) {
      // Some other error (network, serialisation, …) — swallow and leave the caller to decide
      // what to do with a missing Matrix ID.  Do NOT attempt the login probe here, because a
      // generic failure does not imply the account exists.
      log.warn(
          "Failed to create Matrix user {}, skipping fallback: {}",
          matrixLocalpart,
          e.getMessage());
    }

    if (tryFallback) {
      var candidateUserId = "@" + matrixLocalpart + ":" + matrixConfig.getServerName();
      if (!isBlank(matrixSynapseService.loginAsUserAccessToken(candidateUserId))) {
        log.info(
            "Resolved existing Matrix account for localpart {}: {}",
            matrixLocalpart,
            candidateUserId);
        return candidateUserId;
      }
    }

    return null;
  }

  private String buildUniqueSessionRoomAlias(Long sessionId, String consultantId) {
    String safeConsultantId =
        isBlank(consultantId) ? "unknown" : consultantId.replaceAll("[^a-zA-Z0-9]", "");
    return "session_"
        + sessionId
        + "_"
        + safeConsultantId
        + "_"
        + UUID.randomUUID().toString().substring(0, 8);
  }
}
