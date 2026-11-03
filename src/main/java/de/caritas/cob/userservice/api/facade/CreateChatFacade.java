package de.caritas.cob.userservice.api.facade;

import static org.apache.commons.collections4.CollectionUtils.isEmpty;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateChatResponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
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
import de.caritas.cob.userservice.api.service.chat.GroupChatMatrixCleanupService;
import de.caritas.cob.userservice.api.service.chat.GroupCounsellingDpaPolicy;
import de.caritas.cob.userservice.api.service.notification.GroupAppointmentSeriesEventProducer;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Facade to encapsulate the steps for creating a chat. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreateChatFacade {

  private final @NonNull ChatService chatService;
  private final @NonNull GroupAppointmentSeriesEventProducer appointmentEvents;
  private final @NonNull SessionService sessionService;
  private final @NonNull AgencyService agencyService;
  private final @NonNull ChatConverter chatConverter;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull GroupChatParticipantRepository groupChatParticipantRepository;
  private final @NonNull de.caritas.cob.userservice.api.port.out.UserRepository userRepository;
  private final @NonNull AgencySilentMembershipService consultantMembership;
  private final @NonNull GroupCounsellingDpaPolicy groupCounsellingDpaPolicy;
  private final @NonNull GroupChatMatrixCleanupService matrixCleanup;
  private final @NonNull org.springframework.transaction.PlatformTransactionManager transactions;

  /**
   * Creates a group chat in MariaDB and Matrix.
   *
   * @param chatDTO {@link ChatDTO}
   * @param consultant {@link Consultant}
   * @return the generated chat link URL (String)
   */
  @org.springframework.transaction.annotation.Transactional
  public CreateChatResponseDTO createChatV1(ChatDTO chatDTO, Consultant consultant) {
    return createMatrixGroupChat(chatDTO, consultant, null).response();
  }

  /**
   * Creates a chat in MariaDB and Matrix room (simplified flow for group chats).
   *
   * @param chatDTO {@link ChatDTO}
   * @param consultant {@link Consultant}
   * @return the generated chat link URL (String)
   */
  @org.springframework.transaction.annotation.Transactional
  public CreateChatResponseDTO createChatV2(ChatDTO chatDTO, Consultant consultant) {
    return createMatrixGroupChat(chatDTO, consultant, null).response();
  }

  /** The chat that was created: its client response and its id. */
  public record CreatedChat(CreateChatResponseDTO response, Long chatId) {}

  /**
   * Creates the members-only room for a subset of colleagues (ORISO-UserService#1418): an internal
   * group chat with exactly the creator and {@code colleagues}, and nobody else, ever. Unlike an
   * ordinary internal chat it is strict. A colleague who cannot be added fails the whole creation,
   * which then leaves nothing behind in the database or in Matrix. The chat is flagged {@code
   * membersOnly}, so no other colleague can list, open or join it, and its session is no team
   * session, so the team lists of the centre do not show it.
   *
   * @param chatDTO topic and counselling centre of the chat
   * @param creator the calling counsellor
   * @param colleagues the selected colleagues; validated by the caller, never the creator
   */
  @org.springframework.transaction.annotation.Transactional
  public CreatedChat createMembersOnlyChat(
      ChatDTO chatDTO, Consultant creator, List<Consultant> colleagues) {
    return createMatrixGroupChat(chatDTO, creator, List.copyOf(colleagues));
  }

  private void inviteAfterCommit(Chat chat, List<Consultant> selected, String ownerToken) {
    var ids = selected.stream().map(Consultant::getId).toList();
    org.springframework.transaction.support.TransactionSynchronizationManager
        .registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
              @Override
              public void afterCommit() {
                var transaction =
                    new org.springframework.transaction.support.TransactionTemplate(transactions);
                transaction.setPropagationBehavior(
                    org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                for (var id : ids) {
                  try {
                    transaction.executeWithoutResult(
                        status -> {
                          // afterCommit still binds the completed transaction: use a real new
                          // transaction
                          // for fresh identity/role reads and any newly provisioned Matrix mapping.
                          var current =
                              chatService
                                  .getChat(chat.getId())
                                  .orElseThrow(
                                      () ->
                                          new BadRequestException(
                                              "Created group is no longer available"));
                          if (!Objects.equals(current.getMatrixRoomId(), chat.getMatrixRoomId())
                              || !Objects.equals(
                                  current.getChatOwner().getId(), chat.getChatOwner().getId())
                              || !Objects.equals(
                                  current.getChatOwner().getTenantId(),
                                  chat.getChatOwner().getTenantId()))
                            throw new BadRequestException("Created group ownership changed");
                          var actor =
                              consultantRepository
                                  .findByIdAndDeleteDateIsNull(id)
                                  .orElseThrow(
                                      () ->
                                          new BadRequestException(
                                              "Selected consultant is no longer active"));
                          if (!Objects.equals(
                                  current.getChatOwner().getTenantId(), actor.getTenantId())
                              || groupChatParticipantRepository
                                  .findBySeriesId(current.getId())
                                  .stream()
                                  .noneMatch(
                                      p ->
                                          id.equals(p.getConsultantId())
                                              && p.getRole()
                                                  == GroupChatParticipant.ParticipantRole
                                                      .CO_MODERATOR))
                            throw new BadRequestException(
                                "Selected consultant no longer has this assignment");
                          groupCounsellingDpaPolicy.requireNewEnrolment(current);
                          if (!consultantMembership.inviteConsultantIntoRoom(
                              actor, current.getMatrixRoomId(), ownerToken))
                            log.warn(
                                "Self-help group invitation remains pending: failure=InvitationUnavailable");
                        });
                  } catch (Exception failure) {
                    // Metadata/assignment committed; never claim a JOIN or purge this existing
                    // group.
                    // Its normal public JOIN path retries invitation after AVV/provider recovery.
                    log.warn(
                        "Self-help group invitation remains pending: failure={}",
                        failure.getClass().getSimpleName());
                  }
                }
              }
            });
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
  private CreatedChat createMatrixGroupChat(
      ChatDTO chatDTO, Consultant consultant, List<Consultant> membersOnlyColleagues) {
    log.info("Creating group chat with Session + Chat: {}", chatDTO.getTopic());
    final boolean membersOnly = membersOnlyColleagues != null;

    // V1 callers may not carry an agency id or participant list.
    List<String> participantIds =
        chatDTO.getConsultantIds() == null ? List.of() : chatDTO.getConsultantIds();
    Long agencyId = resolveAgencyId(chatDTO, consultant);
    var selectedIds =
        participantIds.stream()
            .distinct()
            .filter(id -> !Objects.equals(id, consultant.getId()))
            .toList();
    if (selectedIds.stream().anyMatch(id -> id == null || id.isBlank())) {
      throw new BadRequestException("Invalid selected consultant");
    }
    var activeById =
        selectedIds.isEmpty()
            ? java.util.Map.<String, Consultant>of()
            : consultantRepository.findByIdInAndDeleteDateIsNull(selectedIds).stream()
                .collect(
                    java.util.stream.Collectors.toMap(
                        Consultant::getId, java.util.function.Function.identity()));
    List<Consultant> participants =
        selectedIds.stream()
            .map(
                id -> {
                  var participant = activeById.get(id);
                  if (participant == null)
                    throw new BadRequestException("Selected consultant is not active");
                  if (consultant.getTenantId() == null
                      || !Objects.equals(consultant.getTenantId(), participant.getTenantId())) {
                    throw new BadRequestException("Selected consultant belongs to another tenant");
                  }
                  return participant;
                })
            .toList();
    groupCounsellingDpaPolicy.requireCreation(chatDTO, agencyId, consultant);
    matrixCleanup.lockOwner(consultant);

    // Create a session for the group (needed for backend logic)
    Session session = new Session();
    session.setConsultant(consultant);
    session.setTenantId(consultant.getTenantId());

    // Use a tenant-scoped system user for group chats (user_id is NOT NULL in database).
    var systemUser = resolveOrCreateGroupChatSystemUser(consultant);
    session.setUser(systemUser);

    // Get consulting type from agency
    AgencyDTO agency = agencyService.getAgency(agencyId);
    Chat chat = chatConverter.convertToEntity(chatDTO, consultant, agency);
    chat.setMembersOnly(membersOnly);
    session.setConsultingTypeId(agency.getConsultingType());

    session.setPostcode("00000"); // Dummy postcode for group chats
    session.setAgencyId(agencyId);
    session.setStatus(SessionStatus.IN_PROGRESS);
    session.setRegistrationType(RegistrationType.REGISTERED);
    // teamSession keeps the group visible through the team-session queries. It is NOT the
    // modality (ADR-006 addendum 2026-09-04): the modality is stamped explicitly here, because
    // this facade is the only producer of INTERNAL_GROUP / SELF_HELP sessions.
    // A members-only chat is no team session: every team session is shown to, and may be opened
    // by, all colleagues of the centre. Its members reach it through their group chat
    // participation.
    session.setTeamSession(!membersOnly);
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

    String matrixRoomId = null;
    Long sessionId = null;
    Long chatId = null;
    Long cleanupTaskId = null;

    // The first writes are inside the guarded block too: whatever fails after the session is
    // saved leaves nothing behind.
    try {
      // Save session to database first
      session = sessionService.saveSession(session);
      sessionId = session.getId();
      log.info("Created session {} for group chat", sessionId);

      // Persist the Chat entity (needed for frontend - has topic field!)
      // A Series is visible immediately, but its occurrence is opened explicitly by a counsellor:
      // keeping it inactive preserves the Waiting Area and makes the opened lifecycle event
      // observable exactly once through StartChatFacade.
      //
      // An internal team chat has no occurrence to open. It is a persistent room for colleagues,
      // so there is nothing to wait for and nobody to open it for — creating it inactive sent
      // counsellors into the askers' Waiting Area, countdown and all. It is open on
      // creation.
      chat.setActive(ConversationType.INTERNAL_GROUP.equals(chat.getConversationType()));
      chat = chatService.saveChat(chat);
      chatId = chat.getId();
      log.info("Created chat {} for group chat", chatId);

      // Create Matrix room with PROPER ALIAS
      String roomName = chatDTO.getTopic();
      String roomAlias = "group_chat_" + sessionId;

      String ownerMatrixUserId = consultantMembership.ensureMatrixAccount(consultant);
      if (ownerMatrixUserId == null || ownerMatrixUserId.isBlank()) {
        throw new InternalServerErrorException("Consultant does not have Matrix credentials");
      }

      var matrixResponse =
          membersOnly
              ? matrixSynapseService.createMembersOnlyRoomAsMatrixUser(
                  roomName, roomAlias, ownerMatrixUserId)
              : matrixSynapseService.createRoomAsMatrixUser(roomName, roomAlias, ownerMatrixUserId);

      matrixRoomId = matrixResponse.getBody().getRoomId();
      cleanupTaskId =
          matrixCleanup.recordRoom(
              new GroupChatMatrixCleanupService.GroupOwner(
                  chatId, consultant.getId(), consultant.getTenantId()),
              matrixRoomId);
      log.info("Created Matrix room: {} for group chat session: {}", matrixRoomId, sessionId);

      // Persist the Matrix room ID on both domain records.
      session.setMatrixRoomId(matrixRoomId);
      // Keep the saved instance: its row version is the one a rollback must delete.
      session = sessionService.saveSession(session);

      chat.setMatrixRoomId(matrixRoomId);
      chatService.saveChat(chat);
      createChatAgencyRelation(chat, agencyId);

      // Get consultant token for inviting others
      String consultantToken = matrixSynapseService.loginAsUserAccessToken(ownerMatrixUserId);
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

      int assignedParticipants = 1;
      if (membersOnly) {
        for (Consultant colleague : membersOnlyColleagues) {
          addColleagueStrictly(matrixRoomId, consultantToken, colleague);
          GroupChatParticipant member = new GroupChatParticipant();
          member.setChatId(sessionId);
          member.setSeriesId(chatId);
          member.setRole(GroupChatParticipant.ParticipantRole.CO_MODERATOR);
          member.setConsultantId(colleague.getId());
          groupChatParticipantRepository.save(member);
          assignedParticipants++;
        }
      }

      boolean selfHelp = ChatConverter.conversationTypeOf(chat) == ConversationType.SELF_HELP;
      // Administrative selection is not commencement. Self-help invitations follow the commit;
      // first participation uses the ordinary, freshly authorized public/native JOIN routes.
      for (Consultant participant : membersOnly ? List.<Consultant>of() : participants) {
        if (selfHelp) {
          groupCounsellingDpaPolicy.requireNewEnrolment(chat);
          var selectedMatrixId = consultantMembership.ensureMatrixAccount(participant);
          if (selectedMatrixId == null || selectedMatrixId.isBlank())
            throw new CustomValidationHttpStatusException(
                HttpStatusExceptionReason.DPA_POLICY_UNAVAILABLE, HttpStatus.BAD_GATEWAY);
        }
        if (!selfHelp
            && !consultantMembership.joinConsultantIntoRoom(
                participant, matrixRoomId, consultantToken)) {
          throw new InternalServerErrorException(
              "Selected consultant could not join the group chat");
        }
        GroupChatParticipant gcp = new GroupChatParticipant();
        gcp.setChatId(sessionId);
        gcp.setSeriesId(chatId);
        gcp.setRole(GroupChatParticipant.ParticipantRole.CO_MODERATOR);
        gcp.setConsultantId(participant.getId());
        groupChatParticipantRepository.save(gcp);
        assignedParticipants++;
      }

      if (selfHelp && !participants.isEmpty())
        inviteAfterCommit(chat, participants, consultantToken);
      appointmentEvents.recordCreated(chat);

      log.info(
          "Successfully created group chat '{}' with Session ID: {}, Chat ID: {}, Matrix room: {} and {} participants",
          chatDTO.getTopic(),
          sessionId,
          chatId,
          matrixRoomId,
          assignedParticipants);

      matrixCleanup.clear(cleanupTaskId);
      return new CreatedChat(
          new CreateChatResponseDTO()
              .matrixRoomId(matrixRoomId)
              .createdAt(session.getCreateDate().toString()),
          chatId);

    } catch (Exception e) {
      log.error("Failed to create group chat: {}", e.getMessage(), e);
      var roomLeftBehind = false;
      if (membersOnly && matrixRoomId != null) {
        // The room is private: do not leave it behind with half of its members.
        roomLeftBehind =
            matrixSynapseService.purgeRoomOrConfirmGone(matrixRoomId)
                == MatrixSynapseService.RoomPurgeOutcome.FAILED;
        if (roomLeftBehind) {
          log.error(
              "Members-only room {} could not be removed after a failed creation and must be"
                  + " purged by an operator",
              matrixRoomId);
        }
      }
      if (cleanupTaskId != null) {
        matrixCleanup.compensate(cleanupTaskId);
      } else if (matrixRoomId != null) {
        // A failed journal write cannot authorize leaving an untracked created room behind.
        try {
          if (!matrixCleanup.purgeUnjournaledRoom(matrixRoomId)) {
            log.error(
                "New group room {} needs manual cleanup: journal and purge failed", matrixRoomId);
          }
        } catch (RuntimeException cleanupFailure) {
          e.addSuppressed(cleanupFailure);
          log.error(
              "New group room {} needs manual cleanup: journal and purge failed", matrixRoomId);
        }
      }
      // Rollback: delete session and chat. A step that fails is remembered: the caller must not be
      // told that nothing was left behind when a record still is.
      var recordsLeftBehind = false;
      if (session.getId() != null) {
        try {
          sessionService.deleteSession(session);
        } catch (Exception ex) {
          recordsLeftBehind = true;
          log.error("Failed to rollback session: {}", ex.getMessage());
        }
      }
      if (chat != null && chat.getId() != null) {
        try {
          chatService.deleteChatRecords(chat);
        } catch (Exception ex) {
          recordsLeftBehind = true;
          log.error("Failed to rollback chat: {}", ex.getMessage());
        }
      }
      if (roomLeftBehind || recordsLeftBehind) {
        // Not the "nothing was left behind" answer: the room or a record is still there.
        throw new InternalServerErrorException(
            "Failed to create group chat and it could not be removed again");
      }
      if (e instanceof CustomValidationHttpStatusException reasoned) {
        throw reasoned;
      }
      throw new InternalServerErrorException("Failed to create group chat: " + e.getMessage());
    }
  }

  /**
   * Adds a colleague to the room of a members-only chat or fails. Whatever goes wrong — the invite,
   * the Matrix login, the join — the colleague is not in the room, and the room must not exist
   * without them.
   */
  private void addColleagueStrictly(
      String matrixRoomId, String creatorToken, Consultant colleague) {
    try {
      matrixSynapseService.inviteUserToRoom(
          matrixRoomId, colleague.getMatrixUserId(), creatorToken);
      String colleagueToken =
          matrixSynapseService.loginAsUserAccessToken(colleague.getMatrixUserId());
      if (colleagueToken != null && matrixSynapseService.joinRoom(matrixRoomId, colleagueToken)) {
        return;
      }
    } catch (Exception failure) {
      log.warn(
          "Colleague {} could not be added to the members-only room: {}",
          colleague.getId(),
          failure.getClass().getSimpleName());
    }
    throw new CustomValidationHttpStatusException(
        HttpStatusExceptionReason.INTERNAL_CHAT_MEMBERSHIP_FAILED, HttpStatus.BAD_GATEWAY);
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
