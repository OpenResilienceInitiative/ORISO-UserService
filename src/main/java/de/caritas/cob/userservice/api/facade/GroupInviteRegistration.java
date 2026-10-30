package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.GROUP_INVITE_INVALID;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.chat.GroupChatInviteTokens;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Registration through a self-help group's invite link (FE#1499). The person joins the group; no
 * counselling enquiry is opened at the group's agency.
 */
@Component
@RequiredArgsConstructor
public class GroupInviteRegistration {

  private final @NonNull ChatAgencyRepository chatAgencyRepository;
  private final @NonNull ChatService chatService;

  /** The group the registration was invited to, or empty for an ordinary registration. */
  public Optional<Chat> resolveInvitedGroup(UserDTO userDTO) {
    if (userDTO.getGroupChatId() == null) {
      return Optional.empty();
    }
    // Every refusal below names GROUP_INVITE_INVALID, so the app can say the link does not work
    // instead of a generic error (#1483). The status codes stay as they were.
    if (userDTO.isConsultantSet()) {
      // A consultant link opens a 1:1 enquiry, which is exactly what a group join must not.
      throw new CustomValidationHttpStatusException(GROUP_INVITE_INVALID, HttpStatus.BAD_REQUEST);
    }
    var chatAgencies = chatAgencyRepository.findByChat_Id(userDTO.getGroupChatId());
    // The link names the group and its agency; registration runs in that agency's tenant.
    Chat group =
        chatAgencies.stream()
            .filter(chatAgency -> chatAgency.getAgencyId().equals(userDTO.getAgencyId()))
            .map(ChatAgency::getChat)
            // Only self-help groups are open to clients; internal groups are counsellors-only.
            .filter(chat -> chat.getConversationType() == ConversationType.SELF_HELP)
            .findFirst()
            // Unknown, deleted, another agency's or not a self-help group: one answer for all.
            .orElseThrow(
                () ->
                    new CustomValidationHttpStatusException(
                        GROUP_INVITE_INVALID, HttpStatus.BAD_REQUEST));
    // The group number is guessable; only the holder of the link may join (#1237), the same
    // check the join by invite link makes.
    if (!GroupChatInviteTokens.matches(group.getInviteToken(), userDTO.getGroupChatInviteToken())) {
      throw new CustomValidationHttpStatusException(GROUP_INVITE_INVALID, HttpStatus.FORBIDDEN);
    }
    return Optional.of(group);
  }

  public void join(Chat group, User user) {
    chatService.saveUserChatRelation(UserChat.builder().user(user).chat(group).build());
  }

  public void leave(Chat group, User user) {
    if (user != null) {
      chatService.deleteUserChatRelation(group, user);
    }
  }
}
