package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.GROUP_INVITE_INVALID;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GuestAccountMode;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.GuestAccountModePolicy;
import de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType;
import de.caritas.cob.userservice.api.service.chat.GroupChatInviteTokens;
import java.util.List;
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
  private final @NonNull GuestAccountModePolicy guestAccountModes;

  /**
   * The circle a registration was invited to and the guest account mode it admits the guest under
   * (#1662): the circle's mode as capped now.
   */
  public record Invitation(Chat group, GuestAccountMode mode) {}

  /** The group the registration was invited to, or empty for an ordinary registration. */
  public Optional<Chat> resolveInvitedGroup(UserDTO userDTO) {
    return resolveInvitation(userDTO).map(Invitation::group);
  }

  /** {@link #resolveInvitedGroup} with the mode the registration joins under. */
  public Optional<Invitation> resolveInvitation(UserDTO userDTO) {
    if (userDTO.getGroupChatId() == null) {
      return Optional.empty();
    }
    // Every refusal below names GROUP_INVITE_INVALID, so the app can say the link does not work
    // instead of a generic error (#1483). The status codes stay as they were.
    if (userDTO.isConsultantSet()) {
      // A consultant link opens a 1:1 enquiry, which is exactly what a group join must not.
      throw new CustomValidationHttpStatusException(GROUP_INVITE_INVALID, HttpStatus.BAD_REQUEST);
    }
    Chat group =
        invitedGroup(
            userDTO.getGroupChatId(), userDTO.getAgencyId(), userDTO.getGroupChatInviteToken());
    // #1662: the circle's guest account mode, capped by what the platform and the circle's Träger
    // allow now: the same Träger the entry page caps with, not the tenant the request resolved to.
    var mode =
        guestAccountModes.cap(
            tenantOf(group),
            userDTO.getAgencyId(),
            InviteLinkChatType.CHAT,
            group.getGuestAccountMode());
    if (!mode.admits(userDTO.isTemporary())) {
      throw new BadRequestException(
          mode == GuestAccountMode.TEMPORARY
              ? "This group invite creates temporary accounts."
              : "This group invite does not create temporary accounts.");
    }
    return Optional.of(new Invitation(group, mode));
  }

  /**
   * #1662: what a Gesprächskreis entry page needs before anybody logs in: the circle's guest
   * account mode, capped by what the platform and the circle's Träger allow now. Only for the
   * holder of the link (group, its Beratungsstelle and the secret), with the same refusals as the
   * registration; nothing else about the circle is returned.
   */
  @org.springframework.transaction.annotation.Transactional(readOnly = true)
  public GuestAccountMode publicGuestAccountMode(Long chatId, Long agencyId, String inviteToken) {
    var group = invitedGroup(chatId, agencyId, inviteToken);
    return guestAccountModes.cap(
        tenantOf(group), agencyId, InviteLinkChatType.CHAT, group.getGuestAccountMode());
  }

  /** The circle's Träger: its owner's tenant, else the current one (single-tenant setups). */
  private static Long tenantOf(Chat group) {
    return group.getChatOwner() != null && group.getChatOwner().getTenantId() != null
        ? group.getChatOwner().getTenantId()
        : de.caritas.cob.userservice.api.tenant.TenantContext.getCurrentTenant();
  }

  /** The self-help group of this Beratungsstelle that the link names, for its holder only. */
  private Chat invitedGroup(Long chatId, Long agencyId, String inviteToken) {
    var chatAgencies =
        chatId == null ? List.<ChatAgency>of() : chatAgencyRepository.findByChat_Id(chatId);
    // The link names the group and its agency; registration runs in that agency's tenant.
    Chat group =
        chatAgencies.stream()
            .filter(chatAgency -> chatAgency.getAgencyId().equals(agencyId))
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
    if (!GroupChatInviteTokens.matches(group.getInviteToken(), inviteToken)) {
      throw new CustomValidationHttpStatusException(GROUP_INVITE_INVALID, HttpStatus.FORBIDDEN);
    }
    return group;
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
