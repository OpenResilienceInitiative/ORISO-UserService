package de.caritas.cob.userservice.api.service;

import static org.apache.commons.lang3.BooleanUtils.isTrue;

import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupChatParticipantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionConsultantForConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateChatResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserChatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionResponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Chat.ChatInterval;
import de.caritas.cob.userservice.api.model.Chat.ChatModality;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.chat.GroupChatParticipantReconciliationService;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Chat service class */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

  private final @NonNull ChatRepository chatRepository;
  private final @NonNull ChatAgencyRepository chatAgencyRepository;
  private final @NonNull UserChatRepository userChatRepository;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull GroupChatParticipantRepository groupChatParticipantRepository;
  private final @NonNull GroupChatParticipantReconciliationService participantReconciliationService;

  private final @NonNull AgencyService agencyService;

  /**
   * Returns a list of current chats for the provided {@link Consultant}
   *
   * @return list of chats as {@link ConsultantSessionResponseDTO}
   */
  public List<ConsultantSessionResponseDTO> getChatsForConsultant(Consultant consultant) {
    Set<Long> agencyIds =
        consultant.getConsultantAgencies().stream()
            .map(ConsultantAgency::getAgencyId)
            .collect(Collectors.toSet());
    log.info(
        "🔍 ChatService.getChatsForConsultant - consultant: {}, agencyIds: {}",
        consultant.getUsername(),
        agencyIds);

    var chats =
        chatRepository.findByAgencyIds(agencyIds).stream()
            .filter(chat -> isVisibleToConsultant(chat, consultant))
            .toList();
    log.info("🔍 ChatService: Found {} chats for agencyIds {}", chats.size(), agencyIds);
    chats.forEach(
        chat ->
            log.info(
                "   - Chat ID: {}, Topic: {}, GroupId: {}, Owner: {}, Active: {}",
                chat.getId(),
                chat.getTopic(),
                chat.getGroupId(),
                chat.getChatOwner() != null ? chat.getChatOwner().getId() : null,
                chat.isActive()));

    var chatAgenciesByChatId = loadChatAgenciesByChatId(chats);

    return chats.stream()
        .map(
            chat ->
                convertChatToConsultantSessionResponseDTO(
                    chat, chatAgenciesByChatId.getOrDefault(chat.getId(), Set.of())))
        .collect(Collectors.toList());
  }

  private boolean isVisibleToConsultant(Chat chat, Consultant consultant) {
    var explicitParticipants = groupChatParticipantRepository.findBySeriesId(chat.getId());
    return explicitParticipants.isEmpty()
        || explicitParticipants.stream()
            .anyMatch(participant -> consultant.getId().equals(participant.getConsultantId()));
  }

  private ConsultantSessionResponseDTO convertChatToConsultantSessionResponseDTO(
      Chat chat, Set<ChatAgency> chatAgencies) {
    return new ConsultantSessionResponseDTO()
        .chat(createUserChat(chat, chatAgencies))
        .consultant(
            new SessionConsultantForConsultantDTO()
                .id(chat.getChatOwner().getId())
                .firstName(chat.getChatOwner().getFirstName())
                .lastName(chat.getChatOwner().getLastName())
                .username(chat.getChatOwner().getUsername()));
  }

  private ConsultantSessionResponseDTO convertChatToConsultantSessionResponseDTO(Chat chat) {
    return convertChatToConsultantSessionResponseDTO(chat, loadChatAgencies(chat.getId()));
  }

  private String[] getChatModerators(Chat chat, Set<ChatAgency> chatAgencies) {
    var explicitParticipants = groupChatParticipantRepository.findBySeriesId(chat.getId());
    if (!explicitParticipants.isEmpty()) {
      return explicitParticipants.stream()
          .filter(
              participant ->
                  participant.getRole() == ParticipantRole.OWNER
                      || participant.getRole() == ParticipantRole.CO_MODERATOR)
          .map(participant -> participant.getConsultantId())
          .toArray(String[]::new);
    }
    return consultantService.findConsultantsByAgencyIds(chatAgencies).stream()
        .map(Consultant::getMatrixUserId)
        .toArray(String[]::new);
  }

  /**
   * Saves a {@link Chat} to MariaDB
   *
   * @param chat {@link Chat}
   * @return {@link Chat} (will never be null)
   */
  public Chat saveChat(Chat chat) {
    if (chat.getConversationType() == null) {
      chat.setConversationType(ConversationType.INTERNAL_GROUP);
    }
    return chatRepository.save(chat);
  }

  /**
   * Saves a {@link ChatAgency} to MariaDB
   *
   * @param chatAgency {@link ChatAgency}
   * @return {@link ChatAgency} (will never be null)
   */
  public ChatAgency saveChatAgencyRelation(ChatAgency chatAgency) {
    return chatAgencyRepository.save(chatAgency);
  }

  /**
   * Saves a {@link UserChat} relation
   *
   * @param userChat {@link UserChat}
   * @return saved {@link UserChat}
   */
  public UserChat saveUserChatRelation(UserChat userChat) {

    if (userChatRepository.findByChatAndUser(userChat.getChat(), userChat.getUser()).isEmpty()) {
      return userChatRepository.save(userChat);
    } else {
      throw new ConflictException("User is already assigned to chat");
    }
  }

  /**
   * Deletes the {@link UserChat} relation between the given chat and user, if one exists. Used when
   * a user leaves a group chat so the assignment does not linger after the leave.
   *
   * @param chat the {@link Chat} that was left
   * @param user the {@link User} who left
   */
  public void deleteUserChatRelation(Chat chat, User user) {
    userChatRepository.findByChatAndUser(chat, user).ifPresent(userChatRepository::delete);
  }

  /**
   * Returns the list of current chats for the provided userId.
   *
   * <p>The chats are collected from the user_agency relation (V1) and the user_chat relation (V2).
   *
   * @param userId the id of the user
   * @return list of user chats as {@link UserSessionResponseDTO}
   */
  public List<UserSessionResponseDTO> getChatsForUserId(String userId) {
    List<Chat> chats =
        chatRepository.findByUserId(userId).stream()
            .filter(chat -> groupChatParticipantRepository.findBySeriesId(chat.getId()).isEmpty())
            .toList();
    List<Chat> assignedChats = chatRepository.findAssignedByUserId(userId);
    var allChats = new ArrayList<>(chats);
    assignedChats.stream()
        .filter(
            assigned ->
                allChats.stream().noneMatch(existing -> existing.getId().equals(assigned.getId())))
        .forEach(allChats::add);
    var chatAgenciesByChatId = loadChatAgenciesByChatId(allChats);
    return allChats.stream()
        .map(
            chat ->
                convertChatToUserSessionResponseDTO(
                    chat, chatAgenciesByChatId.getOrDefault(chat.getId(), Set.of())))
        .collect(Collectors.toList());
  }

  public List<UserSessionResponseDTO> getChatSessionsByIds(Set<Long> chatIds) {
    var chats =
        StreamSupport.stream(chatRepository.findAllById(chatIds).spliterator(), false)
            .collect(Collectors.toList());
    var chatAgenciesByChatId = loadChatAgenciesByChatId(chats);
    return chats.stream()
        .map(
            chat ->
                convertChatToUserSessionResponseDTO(
                    chat, chatAgenciesByChatId.getOrDefault(chat.getId(), Set.of())))
        .collect(Collectors.toList());
  }

  private UserSessionResponseDTO convertChatToUserSessionResponseDTO(
      Chat chat, Set<ChatAgency> chatAgencies) {
    return new UserSessionResponseDTO().chat(createUserChat(chat, chatAgencies));
  }

  private UserSessionResponseDTO convertChatToUserSessionResponseDTO(Chat chat) {
    return convertChatToUserSessionResponseDTO(chat, loadChatAgencies(chat.getId()));
  }

  private UserChatDTO createUserChat(Chat chat) {
    return createUserChat(chat, loadChatAgencies(chat.getId()));
  }

  private UserChatDTO createUserChat(Chat chat, Set<ChatAgency> chatAgencies) {
    if (chatAgencies.size() > 1) {
      log.warn(
          "Chat with id {} has more than one agency assigned. " + "This should not be the case.",
          chat.getId());
    }
    var agencies =
        chatAgencies.stream()
            .map(chatAgency -> agencyService.getAgency(chatAgency.getAgencyId()))
            .collect(Collectors.toList());

    var result =
        new UserChatDTO(
            chat.getId(),
            chat.getTopic(),
            LocalDate.of(
                chat.getStartDate().getYear(),
                chat.getStartDate().getMonth(),
                chat.getStartDate().getDayOfMonth()),
            LocalTime.of(
                chat.getStartDate().getHour(),
                chat.getStartDate().getMinute(),
                chat.getStartDate().getSecond()),
            chat.getDuration(),
            isTrue(chat.isRepetitive()),
            isTrue(chat.isActive()),
            chat.getConsultingTypeId(),
            null,
            null,
            false,
            chat.getGroupId(),
            null,
            false,
            getChatModerators(chat, chatAgencies),
            chat.getStartDate(),
            null,
            chat.getCreateDate() != null ? chat.getCreateDate().toString() : null,
            agencies,
            chat.getHintMessage());
    result.setRepeatCount(chat.getRepeatCount());
    result.setCurrentOccurrenceIndex(chat.getCurrentOccurrenceIndex());
    result.setChatInterval(chat.getChatInterval());
    result.setModality(chat.getChatModality());
    result.setConversationType(chat.getConversationType());
    result.setTimezone(chat.getTimezone());
    result.setParticipants(getSeriesParticipants(chat));
    result.setSourceLanguage(chat.getSourceLanguage());
    result.setHintMessageTranslations(chat.getHintMessageTranslations());
    result.setGroupChatRulesTranslations(chat.getGroupChatRulesTranslations());
    return result;
  }

  private List<GroupChatParticipantDTO> getSeriesParticipants(Chat chat) {
    return groupChatParticipantRepository.findBySeriesId(chat.getId()).stream()
        .map(
            participant -> {
              var displayName =
                  consultantService
                      .getConsultant(participant.getConsultantId())
                      .map(this::resolveParticipantDisplayName)
                      .orElse(participant.getConsultantId());
              return new GroupChatParticipantDTO()
                  .consultantId(participant.getConsultantId())
                  .role(GroupChatParticipantDTO.RoleEnum.fromValue(participant.getRole().name()))
                  .displayName(displayName);
            })
        .toList();
  }

  private String resolveParticipantDisplayName(Consultant consultant) {
    if (consultant.getDisplayName() != null && !consultant.getDisplayName().isBlank()) {
      return consultant.getDisplayName();
    }
    var fullName =
        Stream.of(consultant.getFirstName(), consultant.getLastName())
            .filter(name -> name != null && !name.isBlank())
            .collect(Collectors.joining(" "));
    if (!fullName.isBlank()) {
      return fullName;
    }
    if (consultant.getUsername() != null && !consultant.getUsername().isBlank()) {
      return consultant.getUsername();
    }
    return consultant.getId();
  }

  private Set<ChatAgency> loadChatAgencies(Long chatId) {
    return new HashSet<>(chatAgencyRepository.findByChat_Id(chatId));
  }

  private Map<Long, Set<ChatAgency>> loadChatAgenciesByChatId(List<Chat> chats) {
    var chatIds = chats.stream().map(Chat::getId).collect(Collectors.toSet());
    if (chatIds.isEmpty()) {
      return Map.of();
    }

    return chatAgencyRepository.findByChat_IdIn(chatIds).stream()
        .collect(
            Collectors.groupingBy(
                chatAgency -> chatAgency.getChat().getId(), Collectors.toCollection(HashSet::new)));
  }

  /**
   * Returns an {@link Optional} of {@link Chat} for the provided chat ID.
   *
   * @param chatId chat ID
   * @return {@link Optional} of {@link Chat}
   */
  public Optional<Chat> getChat(Long chatId) {
    return chatRepository.findByIdWithPermissionRelations(chatId);
  }

  /**
   * Returns an {@link Optional} of {@link Chat} for the provided group ID.
   *
   * @param groupId rocket chat group ID
   * @return {@link Optional} of {@link Chat}
   */
  public Optional<Chat> getChatByGroupId(String groupId) {
    return chatRepository.findByGroupId(groupId);
  }

  /**
   * Returns chat sessions for a consultant by chat IDs. This method retrieves chats from the
   * database without checking consultant access - access control should be handled at a higher
   * level (e.g., by checking Matrix room membership or chat ownership).
   *
   * @param chatIds Set of chat IDs
   * @return List of {@link ConsultantSessionResponseDTO}
   */
  public List<ConsultantSessionResponseDTO> getChatSessionsForConsultantByIds(Set<Long> chatIds) {
    log.info("🔍 ChatService.getChatSessionsForConsultantByIds - chatIds: {}", chatIds);

    var chats = chatRepository.findByIdsWithChatAgencies(chatIds);

    log.info("🔍 ChatService: Found {} chats in database", chats.size());
    chats.forEach(
        chat ->
            log.info(
                "   - Chat ID: {}, Topic: {}, GroupId: {}, Owner: {}, Active: {}",
                chat.getId(),
                chat.getTopic(),
                chat.getGroupId(),
                chat.getChatOwner() != null ? chat.getChatOwner().getId() : null,
                chat.isActive()));

    var chatAgenciesByChatId = loadChatAgenciesByChatId(chats);

    var result =
        chats.stream()
            .map(
                chat ->
                    convertChatToConsultantSessionResponseDTO(
                        chat, chatAgenciesByChatId.getOrDefault(chat.getId(), Set.of())))
            .collect(Collectors.toList());

    log.info("🔍 ChatService: Converted to {} ConsultantSessionResponseDTO", result.size());

    return result;
  }

  /**
   * Returns an {@link List} of {@link UserSessionResponseDTO} for the provided group IDs.
   *
   * @param groupIds a list of rocket chat group IDs
   * @return {@link List<UserSessionResponseDTO>}
   */
  public List<UserSessionResponseDTO> getChatSessionsByGroupIds(Set<String> groupIds) {
    var chats = chatRepository.findByGroupIds(groupIds);
    var chatAgenciesByChatId = loadChatAgenciesByChatId(chats);
    return chats.stream()
        .map(
            chat ->
                convertChatToUserSessionResponseDTO(
                    chat, chatAgenciesByChatId.getOrDefault(chat.getId(), Set.of())))
        .collect(Collectors.toList());
  }

  public List<ConsultantSessionResponseDTO> getChatSessionsForConsultantByGroupIds(
      Set<String> groupIds) {
    var chats = chatRepository.findByGroupIds(groupIds);
    var chatAgenciesByChatId = loadChatAgenciesByChatId(chats);
    return chats.stream()
        .map(
            chat ->
                convertChatToConsultantSessionResponseDTO(
                    chat, chatAgenciesByChatId.getOrDefault(chat.getId(), Set.of())))
        .collect(Collectors.toList());
  }

  /**
   * Delete a {@link Chat}
   *
   * @param chat the {@link Chat}
   */
  @Transactional
  public void deleteChat(Chat chat) {
    groupChatParticipantRepository.deleteBySeriesId(chat.getId());
    chatRepository.delete(chat);
  }

  /**
   * Updates topic, duration, repetitive and start date of the provided {@link Chat}.
   *
   * @param chatId chat ID
   * @param chatDTO {@link ChatDTO}
   * @param authenticatedUser {@link AuthenticatedUser}
   * @return {@link UpdateChatResponseDTO}
   */
  @Transactional
  public UpdateChatResponseDTO updateChat(
      Long chatId, ChatDTO chatDTO, AuthenticatedUser authenticatedUser) {

    Chat chat =
        getChat(chatId)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        String.format("Chat with id %s does not exist", chatId)));

    if (!authenticatedUser.getUserId().equals(chat.getChatOwner().getId())) {
      throw new ForbiddenException("Only the chat owner is allowed to change chat settings");
    }
    if (isTrue(chat.isActive())) {
      throw new ConflictException(
          String.format(
              "Chat with id %s is active. Therefore changing the chat settings is not supported.",
              chatId));
    }

    LocalDateTime startDate = LocalDateTime.of(chatDTO.getStartDate(), chatDTO.getStartTime());
    // Timezone drives the recurrence math (occurrenceStart: DST/monthly/yearly). Persist a new
    // one when the client sends it (validated like the create path), and preserve the existing
    // zone when the DTO omits it rather than silently resetting to UTC.
    if (chatDTO.getTimezone() != null && !chatDTO.getTimezone().isBlank()) {
      try {
        ZoneId.of(chatDTO.getTimezone());
      } catch (DateTimeException invalidTimezone) {
        throw new BadRequestException(
            "Invalid timezone: " + chatDTO.getTimezone(), invalidTimezone);
      }
      chat.setTimezone(chatDTO.getTimezone());
    }
    chat.setTopic(chatDTO.getTopic());
    chat.setDuration(chatDTO.getDuration());
    // Defaulting must match the create path (ChatConverter.convertToEntity) so editing a
    // repetitive series without re-sending repeatCount does not silently drop it to a single
    // occurrence: default 12 for repetitive, derive repetitive + interval from repeatCount > 1.
    int repeatCount =
        chatDTO.getRepeatCount() != null
            ? chatDTO.getRepeatCount()
            : (isTrue(chatDTO.getRepetitive()) ? 12 : 1);
    chat.setRepeatCount(repeatCount);
    chat.setRepetitive(repeatCount > 1);
    chat.setChatInterval(
        repeatCount > 1
            ? (chatDTO.getChatInterval() != null ? chatDTO.getChatInterval() : ChatInterval.WEEKLY)
            : null);
    chat.setChatModality(chatDTO.getModality() != null ? chatDTO.getModality() : ChatModality.TEXT);
    chat.setStartDate(startDate);
    chat.setInitialStartDate(startDate);
    // The schedule anchor moved, so virtual occurrences must restart from index 0 —
    // otherwise nextStart() would skip the first occurrences of the edited series.
    chat.setCurrentOccurrenceIndex(0);
    chat.setHintMessage(chatDTO.getHintMessage());
    chat.setSourceLanguage(chatDTO.getSourceLanguage());
    chat.setHintMessageTranslations(chatDTO.getHintMessageTranslations());
    chat.setGroupChatRulesTranslations(chatDTO.getGroupChatRulesTranslations());

    this.saveChat(chat);
    participantReconciliationService.reconcile(chat, chatDTO.getConsultantIds());

    return new UpdateChatResponseDTO().groupId(chat.getGroupId());
  }
}
