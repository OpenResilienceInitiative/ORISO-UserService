package de.caritas.cob.userservice.api.facade;

import static java.util.Objects.nonNull;
import static org.apache.commons.collections4.CollectionUtils.isEmpty;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateChatResponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatAddUserToGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatCreateGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatUserNotInitializedException;
import de.caritas.cob.userservice.api.helper.RocketChatRoomNameGenerator;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.time.LocalDateTime;
import java.util.function.BiFunction;
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
  private final @NonNull RocketChatService rocketChatService;
  private final @NonNull AgencyService agencyService;
  private final @NonNull ChatConverter chatConverter;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull GroupChatParticipantRepository groupChatParticipantRepository;
  private final @NonNull de.caritas.cob.userservice.api.port.out.UserRepository userRepository;
  /**
   * Creates a chat in MariaDB, it's relation to the agency and Rocket.Chat-room (or Matrix room for
   * group chats with consultantIds).
   *
   * @param chatDTO {@link ChatDTO}
   * @param consultant {@link Consultant}
   * @return the generated chat link URL (String)
   */
  public CreateChatResponseDTO createChatV1(ChatDTO chatDTO, Consultant consultant) {
    // Check if this is a simplified group chat creation (with consultantIds)
    if (chatDTO.getConsultantIds() != null && !chatDTO.getConsultantIds().isEmpty()) {
      return createSimplifiedGroupChat(chatDTO, consultant);
    }
    // Otherwise, use the old Rocket.Chat flow
    return createChat(chatDTO, consultant, this::saveChatV1);
  }

  /**
   * Creates a chat in MariaDB and Matrix room (simplified flow for group chats).
   *
   * @param chatDTO {@link ChatDTO}
   * @param consultant {@link Consultant}
   * @return the generated chat link URL (String)
   */
  public CreateChatResponseDTO createChatV2(ChatDTO chatDTO, Consultant consultant) {
    // Check if this is a simplified group chat creation (with consultantIds)
    if (chatDTO.getConsultantIds() != null && !chatDTO.getConsultantIds().isEmpty()) {
      return createSimplifiedGroupChat(chatDTO, consultant);
    }
    // Otherwise, use the old flow
    return createChat(chatDTO, consultant, this::saveChatV2);
  }

  private CreateChatResponseDTO createChat(
      ChatDTO chatDTO, Consultant consultant, BiFunction<Consultant, ChatDTO, Chat> saveChat) {
    Chat chat = null;
    String rcGroupId = null;

    try {
      chat = saveChat.apply(consultant, chatDTO);
      rcGroupId = createRocketChatGroupWithTechnicalUser(chatDTO, chat);
      chat.setGroupId(rcGroupId);
      chatService.saveChat(chat);

      return new CreateChatResponseDTO()
          .groupId(rcGroupId)
          .createdAt(chat.getCreateDate().toString());
    } catch (InternalServerErrorException e) {
      doRollback(chat, rcGroupId);
      throw e;
    }
  }

  private Chat saveChatV1(Consultant consultant, ChatDTO chatDTO) {
    if (isEmpty(consultant.getConsultantAgencies())) {
      throw new InternalServerErrorException(
          String.format("Consultant with id %s is not assigned to any agency", consultant.getId()));
    }
    Long agencyId = consultant.getConsultantAgencies().iterator().next().getAgencyId();
    AgencyDTO agency = this.agencyService.getAgency(agencyId);

    Chat chat = chatService.saveChat(chatConverter.convertToEntity(chatDTO, consultant, agency));
    createChatAgencyRelation(chat, agencyId);
    return chat;
  }

  private Chat saveChatV2(Consultant consultant, ChatDTO chatDTO) {
    assertAgencyIdIsNotNull(chatDTO);
    Chat chat = chatService.saveChat(chatConverter.convertToEntity(chatDTO, consultant));
    ConsultantAgency foundConsultantAgency =
        findConsultantAgencyForGivenChatAgency(consultant, chatDTO);
    createChatAgencyRelation(chat, foundConsultantAgency.getAgencyId());
    return chat;
  }

  private void assertAgencyIdIsNotNull(ChatDTO chatDTO) {
    if (chatDTO.getAgencyId() == null) {
      throw new BadRequestException("Agency id must not be null");
    }
  }

  private ConsultantAgency findConsultantAgencyForGivenChatAgency(
      Consultant consultant, ChatDTO chatDTO) {
    return consultant.getConsultantAgencies().stream()
        .filter(agency -> agency.getAgencyId().equals(chatDTO.getAgencyId()))
        .findFirst()
        .orElseThrow(
            () ->
                new BadRequestException(
                    String.format(
                        "Consultant with id %s is not assigned to agency with id %s",
                        consultant.getId(), chatDTO.getAgencyId())));
  }

  private void createChatAgencyRelation(Chat chat, Long agencyId) {
    chatService.saveChatAgencyRelation(new ChatAgency(chat, agencyId));
  }

  private String createRocketChatGroupWithTechnicalUser(ChatDTO chatDTO, Chat chat) {
    String rcGroupId = createRocketChatGroup(chatDTO, chat);
    addTechnicalUserToGroup(chat, rcGroupId);
    return rcGroupId;
  }

  private void addTechnicalUserToGroup(Chat chat, String rcGroupId) {
    try {
      rocketChatService.addTechnicalUserToGroup(rcGroupId);
    } catch (RocketChatAddUserToGroupException e) {
      doRollback(chat, rcGroupId);
      throw new InternalServerErrorException(
          "Technical user could not be added to group chat " + "room");
    } catch (RocketChatUserNotInitializedException e) {
      doRollback(chat, rcGroupId);
      throw new InternalServerErrorException("Rocket chat user is not initialized");
    }
  }

  private String createRocketChatGroup(ChatDTO chatDTO, Chat chat) {
    try {
      GroupResponseDTO rcGroupDTO =
          rocketChatService
              .createPrivateGroupWithSystemUser(
                  new RocketChatRoomNameGenerator().generateGroupChatName(chat))
              .orElseThrow(
                  () ->
                      new RocketChatCreateGroupException(
                          "RocketChat group is not present while creating chat: " + chatDTO));
      return rcGroupDTO.getGroup().getId();
    } catch (RocketChatCreateGroupException e) {
      doRollback(chat, null);
      throw new InternalServerErrorException(
          "Error while creating private group in Rocket.Chat for group chat: "
              + chatDTO.toString());
    }
  }

  private void doRollback(Chat chat, String rcGroupId) {
    if (nonNull(chat)) {
      chatService.deleteChat(chat);
    }
    if (nonNull(rcGroupId)) {
      rocketChatService.deleteGroupAsSystemUser(rcGroupId);
    }
  }

  /**
   * Creates a simplified group chat with BOTH Session and Chat entities. Session is needed for the
   * backend logic, Chat is needed for the frontend (topic field).
   *
   * @param chatDTO {@link ChatDTO} with consultantIds
   * @param consultant {@link Consultant} the creator
   * @return {@link CreateChatResponseDTO}
   */
  private CreateChatResponseDTO createSimplifiedGroupChat(ChatDTO chatDTO, Consultant consultant) {
    log.info("Creating group chat with Session + Chat: {}", chatDTO.getTopic());

    // Create a session for the group (needed for backend logic)
    Session session = new Session();
    session.setConsultant(consultant);

    // Use system user for group chats (user_id is NOT NULL in database)
    var systemUser =
        userRepository
            .findByUserIdAndDeleteDateIsNull("group-chat-system")
            .orElseThrow(
                () -> new InternalServerErrorException("System user not found for group chats"));
    session.setUser(systemUser);

    // Get consulting type from agency
    AgencyDTO agency = agencyService.getAgency(chatDTO.getAgencyId());
    session.setConsultingTypeId(agency.getConsultingType());

    session.setPostcode("00000"); // Dummy postcode for group chats
    session.setAgencyId(chatDTO.getAgencyId());
    session.setStatus(SessionStatus.IN_PROGRESS);
    session.setRegistrationType(RegistrationType.REGISTERED);
    session.setTeamSession(true); // Mark as group chat
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

    // Create a Chat entity (needed for frontend - has topic field!)
    Chat chat = chatConverter.convertToEntity(chatDTO, consultant, agency);
    chat.setActive(true); // Mark as active
    chat = chatService.saveChat(chat);
    Long chatId = chat.getId();
    log.info("Created chat {} for group chat", chatId);

    // Create chat-agency relation
    createChatAgencyRelation(chat, chatDTO.getAgencyId());

    String matrixRoomId = null;

    try {
      // Create Matrix room with PROPER ALIAS
      String roomName = chatDTO.getTopic();
      String roomAlias = "group_chat_" + sessionId;

      // Extract consultant username
      String consultantMatrixUsername = null;
      if (consultant.getMatrixUserId() != null && consultant.getMatrixUserId().startsWith("@")) {
        consultantMatrixUsername = consultant.getMatrixUserId().substring(1).split(":")[0];
      }

      String consultantPassword = consultant.getMatrixPassword();
      if (consultantPassword == null) {
        throw new InternalServerErrorException("Consultant does not have Matrix credentials");
      }

      // Create room using the WORKING method (same as 1-on-1)
      var matrixResponse =
          matrixSynapseService.createRoomAsConsultant(
              roomName, roomAlias, consultantMatrixUsername, consultantPassword);

      matrixRoomId = matrixResponse.getBody().getRoomId();
      log.info("Created Matrix room: {} for group chat session: {}", matrixRoomId, sessionId);

      // Update BOTH session and chat with Matrix room ID
      session.setMatrixRoomId(matrixRoomId);
      session.setGroupId(matrixRoomId);
      sessionService.saveSession(session);

      chat.setGroupId(matrixRoomId); // Set rc_group_id for backwards compatibility
      chat.setMatrixRoomId(matrixRoomId); // Set matrix_room_id for Matrix
      chatService.saveChat(chat);

      // Get consultant token for inviting others
      String consultantToken =
          matrixSynapseService.loginUser(consultantMatrixUsername, consultantPassword);

      // IMPORTANT: Add the CREATOR to group_chat_participant table!
      GroupChatParticipant creatorParticipant = new GroupChatParticipant();
      creatorParticipant.setChatId(chatId); // Link to CHAT ID (not session ID!)
      creatorParticipant.setConsultantId(consultant.getId());
      groupChatParticipantRepository.save(creatorParticipant);
      log.info("Added creator consultant {} to group_chat_participant", consultant.getId());

      // Invite and auto-join all selected consultants
      for (String participantId : chatDTO.getConsultantIds()) {
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
          String participantUsername = participant.getMatrixUserId().substring(1).split(":")[0];
          String participantToken =
              matrixSynapseService.loginUser(participantUsername, participant.getMatrixPassword());
          if (participantToken != null) {
            matrixSynapseService.joinRoom(matrixRoomId, participantToken);
            log.info("Consultant {} joined group chat room: {}", participantId, matrixRoomId);
          }

          // Save participant in group_chat_participant table (for querying who's in the group)
          GroupChatParticipant gcp = new GroupChatParticipant();
          gcp.setChatId(sessionId); // Link to session ID
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
          chatDTO.getConsultantIds().size() + 1); // +1 for creator

      return new CreateChatResponseDTO()
          .groupId(matrixRoomId)
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
}
