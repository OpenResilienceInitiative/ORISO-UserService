package de.caritas.cob.userservice.api.facade;

import static org.apache.commons.collections4.CollectionUtils.isEmpty;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateChatResponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Facade to encapsulate the steps for creating a chat. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreateChatFacade {

  private final @NonNull ChatService chatService;
  private final @NonNull SessionService sessionService;
  private final @NonNull AgencyService agencyService;
  private final @NonNull ChatConverter chatConverter;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull GroupChatParticipantRepository groupChatParticipantRepository;
  private final @NonNull de.caritas.cob.userservice.api.port.out.UserRepository userRepository;

  /**
   * Creates a group chat in MariaDB and Matrix.
   *
   * @param chatDTO {@link ChatDTO}
   * @param consultant {@link Consultant}
   * @return the generated chat link URL (String)
   */
  public CreateChatResponseDTO createChatV1(ChatDTO chatDTO, Consultant consultant) {
    return createMatrixGroupChat(chatDTO, consultant);
  }

  /**
   * Creates a chat in MariaDB and Matrix room (simplified flow for group chats).
   *
   * @param chatDTO {@link ChatDTO}
   * @param consultant {@link Consultant}
   * @return the generated chat link URL (String)
   */
  public CreateChatResponseDTO createChatV2(ChatDTO chatDTO, Consultant consultant) {
    return createMatrixGroupChat(chatDTO, consultant);
  }

  private void createChatAgencyRelation(Chat chat, Long agencyId) {
    chatService.saveChatAgencyRelation(new ChatAgency(chat, agencyId));
  }

  /**
   * Creates a simplified group chat with BOTH Session and Chat entities. Session is needed for the
   * backend logic, Chat is needed for the frontend (topic field).
   *
   * @param chatDTO {@link ChatDTO} with consultantIds
   * @param consultant {@link Consultant} the creator
   * @return {@link CreateChatResponseDTO}
   */
  private CreateChatResponseDTO createMatrixGroupChat(ChatDTO chatDTO, Consultant consultant) {
    log.info("Creating group chat with Session + Chat: {}", chatDTO.getTopic());

    // V1 callers may not carry an agency id or participant list.
    List<String> participantIds =
        chatDTO.getConsultantIds() == null ? List.of() : chatDTO.getConsultantIds();
    Long agencyId = resolveAgencyId(chatDTO, consultant);

    // Create a session for the group (needed for backend logic)
    Session session = new Session();
    session.setConsultant(consultant);

    // Use a tenant-scoped system user for group chats (user_id is NOT NULL in database).
    var systemUser = resolveOrCreateGroupChatSystemUser(consultant);
    session.setUser(systemUser);

    // Get consulting type from agency
    AgencyDTO agency = agencyService.getAgency(agencyId);
    Chat chat = chatConverter.convertToEntity(chatDTO, consultant, agency);
    session.setConsultingTypeId(agency.getConsultingType());

    session.setPostcode("00000"); // Dummy postcode for group chats
    session.setAgencyId(agencyId);
    session.setStatus(SessionStatus.IN_PROGRESS);
    session.setRegistrationType(RegistrationType.REGISTERED);
    // teamSession keeps the group visible through the team-session queries. It is NOT the
    // modality (ADR-006 addendum 2026-09-04): the modality is stamped explicitly here, because
    // this facade is the only producer of INTERNAL_GROUP / SELF_HELP sessions.
    session.setTeamSession(true);
    session.setConversationType(
        chat.getConversationType() == null
            ? ConversationType.INTERNAL_GROUP
            : chat.getConversationType());
    session.setLanguageCode(LanguageCode.de); // Default language
    session.setIsConsultantDirectlySet(false); // Not directly assigned

    // Set timestamps manually (database default doesn't work with JPA)
    LocalDateTime now = LocalDateTime.now();
    session.setCreateDate(now);
    session.setUpdateDate(now);

    // Save session to database first
    session = sessionService.saveSession(session);
    Long sessionId = session.getId();
    log.info("Created session {} for group chat", sessionId);

    // Persist the Chat entity (needed for frontend - has topic field!)
    // A Series is visible immediately, but its occurrence is opened explicitly by a counsellor:
    // keeping it inactive preserves the Waiting Area and makes the opened lifecycle event
    // observable exactly once through StartChatFacade.
    //
    // An internal team chat has no occurrence to open. It is a persistent room for colleagues,
    // so there is nothing to wait for and nobody to open it for — creating it inactive sent
    // counsellors into the askers' Waiting Area, countdown and all (#979). It is open on
    // creation.
    chat.setActive(ConversationType.INTERNAL_GROUP.equals(chat.getConversationType()));
    chat = chatService.saveChat(chat);
    Long chatId = chat.getId();
    log.info("Created chat {} for group chat", chatId);

    String matrixRoomId = null;

    try {
      // Create Matrix room with PROPER ALIAS
      String roomName = chatDTO.getTopic();
      String roomAlias = "group_chat_" + sessionId;

      if (consultant.getMatrixUserId() == null || consultant.getMatrixUserId().isBlank()) {
        throw new InternalServerErrorException("Consultant does not have Matrix credentials");
      }

      var matrixResponse =
          matrixSynapseService.createRoomAsMatrixUser(
              roomName, roomAlias, consultant.getMatrixUserId());

      matrixRoomId = matrixResponse.getBody().getRoomId();
      log.info("Created Matrix room: {} for group chat session: {}", matrixRoomId, sessionId);

      // Persist the Matrix room ID on both domain records.
      session.setMatrixRoomId(matrixRoomId);
      sessionService.saveSession(session);

      chat.setMatrixRoomId(matrixRoomId);
      chatService.saveChat(chat);
      createChatAgencyRelation(chat, agencyId);

      // Get consultant token for inviting others
      String consultantToken =
          matrixSynapseService.loginAsUserAccessToken(consultant.getMatrixUserId());
      if (consultantToken == null) {
        throw new InternalServerErrorException("Could not create Matrix token for consultant");
      }

      // IMPORTANT: Add the CREATOR to group_chat_participant table!
      GroupChatParticipant creatorParticipant = new GroupChatParticipant();
      creatorParticipant.setChatId(sessionId); // consistent with other participants
      creatorParticipant.setSeriesId(chatId);
      creatorParticipant.setRole(GroupChatParticipant.ParticipantRole.OWNER);
      creatorParticipant.setConsultantId(consultant.getId());
      groupChatParticipantRepository.save(creatorParticipant);
      log.info("Added creator consultant {} to group_chat_participant", consultant.getId());

      // Invite and auto-join all selected consultants
      for (String participantId : participantIds) {
        try {
          Consultant participant = consultantRepository.findById(participantId).orElse(null);
          if (participant == null) {
            log.warn("Consultant {} not found, skipping", participantId);
            continue;
          }

          // Invite to Matrix room
          matrixSynapseService.inviteUserToRoom(
              matrixRoomId, participant.getMatrixUserId(), consultantToken);

          // Auto-join the participant
          String participantToken =
              matrixSynapseService.loginAsUserAccessToken(participant.getMatrixUserId());
          if (participantToken != null) {
            matrixSynapseService.joinRoom(matrixRoomId, participantToken);
            log.info("Consultant {} joined group chat room: {}", participantId, matrixRoomId);
          }

          // Save participant in group_chat_participant table (for querying who's in the group)
          GroupChatParticipant gcp = new GroupChatParticipant();
          gcp.setChatId(sessionId); // Link to session ID
          gcp.setSeriesId(chatId);
          gcp.setRole(GroupChatParticipant.ParticipantRole.CO_MODERATOR);
          gcp.setConsultantId(participantId);
          groupChatParticipantRepository.save(gcp);

        } catch (Exception e) {
          log.error(
              "Failed to invite consultant {} to group chat: {}", participantId, e.getMessage());
          // Continue with other participants
        }
      }

      log.info(
          "Successfully created group chat '{}' with Session ID: {}, Chat ID: {}, Matrix room: {} and {} participants",
          chatDTO.getTopic(),
          sessionId,
          chatId,
          matrixRoomId,
          participantIds.size() + 1); // +1 for creator

      return new CreateChatResponseDTO()
          .matrixRoomId(matrixRoomId)
          .createdAt(session.getCreateDate().toString());

    } catch (Exception e) {
      log.error("Failed to create group chat: {}", e.getMessage(), e);
      // Rollback: delete session and chat
      if (session.getId() != null) {
        try {
          sessionService.deleteSession(session);
        } catch (Exception ex) {
          log.error("Failed to rollback session: {}", ex.getMessage());
        }
      }
      if (chat != null && chat.getId() != null) {
        try {
          chatService.deleteChat(chat);
        } catch (Exception ex) {
          log.error("Failed to rollback chat: {}", ex.getMessage());
        }
      }
      throw new InternalServerErrorException("Failed to create group chat: " + e.getMessage());
    }
  }

  /**
   * Resolves the agency for a group chat: prefer the explicitly requested agency (V2 semantics),
   * fall back to the consultant's first agency (V1 semantics).
   */
  private Long resolveAgencyId(ChatDTO chatDTO, Consultant consultant) {
    if (chatDTO.getAgencyId() != null) {
      return chatDTO.getAgencyId();
    }
    if (isEmpty(consultant.getConsultantAgencies())) {
      throw new InternalServerErrorException(
          String.format("Consultant with id %s is not assigned to any agency", consultant.getId()));
    }
    return consultant.getConsultantAgencies().iterator().next().getAgencyId();
  }

  private User resolveOrCreateGroupChatSystemUser(Consultant consultant) {
    Long tenantId = consultant.getTenantId();
    String tenantScopedSystemUserId =
        "group-chat-system-" + (tenantId == null ? "default" : tenantId);

    return userRepository
        .findByUserIdAndDeleteDateIsNull(tenantScopedSystemUserId)
        .or(() -> userRepository.findByUserIdAndDeleteDateIsNull("group-chat-system"))
        .orElseGet(
            () -> {
              log.warn(
                  "Group chat system user not found for tenant {}. Creating fallback system user {}.",
                  tenantId,
                  tenantScopedSystemUserId);
              User fallbackSystemUser =
                  new User(
                      tenantScopedSystemUserId,
                      null,
                      tenantScopedSystemUserId,
                      tenantScopedSystemUserId + "@oriso.local",
                      true);
              fallbackSystemUser.setTenantId(tenantId);
              fallbackSystemUser.setNotificationsEnabled(false);
              return userRepository.save(fallbackSystemUser);
            });
  }
}
