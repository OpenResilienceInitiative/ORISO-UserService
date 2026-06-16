package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class UserChatControllerDelegateTest {

  @Mock private UserAccountService userAccountProvider;
  @Mock private ChatService chatService;
  @Mock private StartChatFacade startChatFacade;
  @Mock private GetChatFacade getChatFacade;
  @Mock private JoinAndLeaveChatFacade joinAndLeaveChatFacade;
  @Mock private AssignChatFacade assignChatFacade;
  @Mock private CreateChatFacade createChatFacade;
  @Mock private StopChatFacade stopChatFacade;
  @Mock private GetChatMembersFacade getChatMembersFacade;
  @Mock private AccountManaging accountManager;
  @Mock private Messaging messenger;
  @Mock private UserDtoMapper userDtoMapper;
  @Mock private AuthenticatedUser authenticatedUser;

  @InjectMocks private UserChatControllerDelegate delegate;

  @Test
  void createChatV1ShouldReturnCreatedResponseFromFacade() {
    var chatDTO = new ChatDTO();
    var consultant = consultant();
    var createChatResponseDTO = new CreateChatResponseDTO();
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
    when(createChatFacade.createChatV1(chatDTO, consultant)).thenReturn(createChatResponseDTO);

    var response = delegate.createChatV1(chatDTO);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isSameAs(createChatResponseDTO);
  }

  @Test
  void createChatV2ShouldReturnCreatedResponseFromFacade() {
    var chatDTO = new ChatDTO();
    var consultant = consultant();
    var createChatResponseDTO = new CreateChatResponseDTO();
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
    when(createChatFacade.createChatV2(chatDTO, consultant)).thenReturn(createChatResponseDTO);

    var response = delegate.createChatV2(chatDTO);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isSameAs(createChatResponseDTO);
  }

  @Test
  void startChatShouldStartExistingChatAndReturnOk() {
    var chat = chat();
    var consultant = consultant();
    when(chatService.getChat(1L)).thenReturn(Optional.of(chat));
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);

    var response = delegate.startChat(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(startChatFacade).startChat(chat, consultant);
  }

  @Test
  void startChatShouldThrowBadRequestWhenChatDoesNotExist() {
    when(chatService.getChat(1L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> delegate.startChat(1L)).isInstanceOf(BadRequestException.class);

    verify(chatService).getChat(1L);
  }

  @Test
  void getChatShouldReturnOkAndEnrichBannedUsersWhenMetadataExists() {
    var chatInfoResponseDTO = new ChatInfoResponseDTO();
    var metadata = Map.<String, Object>of("banned", List.of("user-id"));
    when(getChatFacade.getChat(1L)).thenReturn(chatInfoResponseDTO);
    when(authenticatedUser.getUserId()).thenReturn("consultant-id");
    when(messenger.findChatMetaInfo(1L, "consultant-id")).thenReturn(Optional.of(metadata));
    when(userDtoMapper.bannedChatUserIdsOf(metadata)).thenReturn(List.of("banned-user"));

    var response = delegate.getChat(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(chatInfoResponseDTO);
    assertThat(chatInfoResponseDTO.getBannedUsers()).containsExactly("banned-user");
  }

  @Test
  void assignChatShouldDelegateAndReturnOk() {
    var response = delegate.assignChat("group-id");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(assignChatFacade).assignChat("group-id", authenticatedUser);
  }

  @Test
  void joinChatShouldDelegateAndReturnOk() {
    var response = delegate.joinChat(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(joinAndLeaveChatFacade).joinChat(1L, authenticatedUser);
  }

  @Test
  void verifyCanModerateChatShouldDelegateAndReturnOk() {
    var response = delegate.verifyCanModerateChat(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(joinAndLeaveChatFacade).verifyCanModerate(1L);
  }

  @Test
  void stopChatShouldUnbanStopAndReturnOk() {
    var chat = chat();
    var consultant = consultant();
    when(chatService.getChat(1L)).thenReturn(Optional.of(chat));
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);

    var response = delegate.stopChat(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(messenger).unbanUsersInChat(1L, "consultant-id");
    verify(stopChatFacade).stopChat(chat, consultant);
  }

  @Test
  void stopChatShouldThrowBadRequestWhenChatDoesNotExist() {
    when(chatService.getChat(1L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> delegate.stopChat(1L)).isInstanceOf(BadRequestException.class);

    verify(chatService).getChat(1L);
  }

  @Test
  void getChatMembersShouldReturnOkWithFacadeResponse() {
    var chatMembersResponseDTO = new ChatMembersResponseDTO();
    when(getChatMembersFacade.getChatMembers(1L)).thenReturn(chatMembersResponseDTO);

    var response = delegate.getChatMembers(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(chatMembersResponseDTO);
  }

  @Test
  void leaveChatShouldDelegateAndReturnOk() {
    var response = delegate.leaveChat(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(joinAndLeaveChatFacade).leaveChat(1L, authenticatedUser);
  }

  @Test
  void updateChatShouldReturnOkWithServiceResponse() {
    var chatDTO = new ChatDTO();
    var updateChatResponseDTO = new UpdateChatResponseDTO();
    when(chatService.updateChat(1L, chatDTO, authenticatedUser)).thenReturn(updateChatResponseDTO);

    var response = delegate.updateChat(1L, chatDTO);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(updateChatResponseDTO);
  }

  @Test
  void banFromChatShouldBanAdviceSeekerAndReturnNoContent() {
    var adviceSeeker = adviceSeeker();
    when(accountManager.findAdviceSeekerByChatUserId("chat-user-id"))
        .thenReturn(Optional.of(adviceSeeker));
    when(messenger.existsChat(1L)).thenReturn(true);
    when(messenger.banUserFromChat("advice-seeker-id", 1L)).thenReturn(true);

    var response = delegate.banFromChat("chat-user-id", 1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(messenger).banUserFromChat("advice-seeker-id", 1L);
  }

  @Test
  void banFromChatShouldThrowNotFoundWhenAdviceSeekerDoesNotExist() {
    when(accountManager.findAdviceSeekerByChatUserId("chat-user-id")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> delegate.banFromChat("chat-user-id", 1L))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void banFromChatShouldThrowNotFoundWhenChatDoesNotExist() {
    when(accountManager.findAdviceSeekerByChatUserId("chat-user-id"))
        .thenReturn(Optional.of(adviceSeeker()));
    when(messenger.existsChat(1L)).thenReturn(false);

    assertThatThrownBy(() -> delegate.banFromChat("chat-user-id", 1L))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void banFromChatShouldThrowNotFoundWhenBanFails() {
    when(accountManager.findAdviceSeekerByChatUserId("chat-user-id"))
        .thenReturn(Optional.of(adviceSeeker()));
    when(messenger.existsChat(1L)).thenReturn(true);
    when(messenger.banUserFromChat(any(), anyLong())).thenReturn(false);

    assertThatThrownBy(() -> delegate.banFromChat("chat-user-id", 1L))
        .isInstanceOf(NotFoundException.class);
  }

  private Chat chat() {
    var now = LocalDateTime.now();
    return Chat.builder().id(1L).topic("topic").initialStartDate(now).startDate(now).build();
  }

  private User adviceSeeker() {
    return User.builder()
        .userId("advice-seeker-id")
        .username("advice-seeker")
        .email("advice-seeker@example.com")
        .build();
  }

  private Consultant consultant() {
    return Consultant.builder()
        .id("consultant-id")
        .rocketChatId("rocket-chat-id")
        .username("consultant")
        .firstName("Con")
        .lastName("Sultant")
        .email("consultant@example.com")
        .build();
  }
}
