package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatInfoResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatMembersResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateChatResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateChatResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.facade.AssignChatFacade;
import de.caritas.cob.userservice.api.facade.CreateChatFacade;
import de.caritas.cob.userservice.api.facade.GetChatFacade;
import de.caritas.cob.userservice.api.facade.GetChatMembersFacade;
import de.caritas.cob.userservice.api.facade.JoinAndLeaveChatFacade;
import de.caritas.cob.userservice.api.facade.StartChatFacade;
import de.caritas.cob.userservice.api.facade.StopChatFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class UserChatControllerDelegate {

  private final @NonNull UserAccountService userAccountProvider;
  private final @NonNull ChatService chatService;
  private final @NonNull StartChatFacade startChatFacade;
  private final @NonNull GetChatFacade getChatFacade;
  private final @NonNull JoinAndLeaveChatFacade joinAndLeaveChatFacade;
  private final @NonNull AssignChatFacade assignChatFacade;
  private final @NonNull CreateChatFacade createChatFacade;
  private final @NonNull StopChatFacade stopChatFacade;
  private final @NonNull GetChatMembersFacade getChatMembersFacade;
  private final @NonNull AccountManaging accountManager;
  private final @NonNull Messaging messenger;
  private final @NonNull UserDtoMapper userDtoMapper;
  private final @NonNull AuthenticatedUser authenticatedUser;

  ResponseEntity<CreateChatResponseDTO> createChatV1(ChatDTO chatDTO) {
    var callingConsultant = this.userAccountProvider.retrieveValidatedConsultant();
    var response = createChatFacade.createChatV1(chatDTO, callingConsultant);

    return new ResponseEntity<>(response, HttpStatus.CREATED);
  }

  ResponseEntity<CreateChatResponseDTO> createChatV2(ChatDTO chatDTO) {
    var callingConsultant = this.userAccountProvider.retrieveValidatedConsultant();
    var response = createChatFacade.createChatV2(chatDTO, callingConsultant);
    return new ResponseEntity<>(response, HttpStatus.CREATED);
  }

  ResponseEntity<Void> startChat(Long chatId) {
    var chat =
        chatService
            .getChat(chatId)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        String.format("Chat with id %s not found for starting chat.", chatId)));

    var callingConsultant = this.userAccountProvider.retrieveValidatedConsultant();
    startChatFacade.startChat(chat, callingConsultant);

    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<ChatInfoResponseDTO> getChat(Long chatId) {
    var response = getChatFacade.getChat(chatId);
    messenger
        .findChatMetaInfo(chatId, authenticatedUser.getUserId())
        .ifPresent(
            chatMetaInfoMap -> {
              var bannedChatUserIds = userDtoMapper.bannedChatUserIdsOf(chatMetaInfoMap);
              response.setBannedUsers(bannedChatUserIds);
            });

    return new ResponseEntity<>(response, HttpStatus.OK);
  }

  ResponseEntity<Void> assignChat(String groupId) {
    assignChatFacade.assignChat(groupId, authenticatedUser);

    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<Void> joinChat(Long chatId) {
    joinAndLeaveChatFacade.joinChat(chatId, authenticatedUser);
    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<Void> verifyCanModerateChat(Long chatId) {
    joinAndLeaveChatFacade.verifyCanModerate(chatId);
    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<Void> stopChat(Long chatId) {
    var chat =
        chatService
            .getChat(chatId)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        String.format(
                            "Chat with id %s not found while trying to stop the chat.", chatId)));

    var callingConsultant = this.userAccountProvider.retrieveValidatedConsultant();
    messenger.unbanUsersInChat(chatId, callingConsultant.getId());
    stopChatFacade.stopChat(chat, callingConsultant);

    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<ChatMembersResponseDTO> getChatMembers(Long chatId) {
    var chatMembersResponseDTO = getChatMembersFacade.getChatMembers(chatId);

    return new ResponseEntity<>(chatMembersResponseDTO, HttpStatus.OK);
  }

  ResponseEntity<Void> leaveChat(Long chatId) {
    joinAndLeaveChatFacade.leaveChat(chatId, authenticatedUser);

    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<UpdateChatResponseDTO> updateChat(Long chatId, ChatDTO chatDTO) {
    var updateChatResponseDTO = chatService.updateChat(chatId, chatDTO, authenticatedUser);
    return new ResponseEntity<>(updateChatResponseDTO, HttpStatus.OK);
  }

  ResponseEntity<Void> banFromChat(String chatUserId, Long chatId) {
    var adviceSeeker =
        accountManager
            .findAdviceSeekerByChatUserId(chatUserId)
            .orElseThrow(
                () -> {
                  throw new NotFoundException("Chat User (%s) not found", chatUserId);
                });
    if (!messenger.existsChat(chatId)) {
      throw new NotFoundException("Chat (%s) not found", chatId);
    }

    var adviceSeekerId = adviceSeeker.getUserId();
    if (!messenger.banUserFromChat(adviceSeekerId, chatId)) {
      throw new NotFoundException("User (%s) not found in Chat (%s)", adviceSeekerId, chatId);
    }

    return ResponseEntity.noContent().build();
  }
}
