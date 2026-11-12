package de.caritas.cob.userservice.api.facade;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.dto.InternalChatRecipientDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.chat.MemberSetLock;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Brings a colleague into a running members-only internal chat (ORISO-UserService#1580).
 *
 * <p>Who may invite: anyone who takes part in the chat right now. Who may be invited: exactly the
 * people a new chat of the room's owner at the room's counselling centre could hold — the recipient
 * scope is anchored to the room, never to the person who asks, so a participant from another Träger
 * cannot widen it. A room that would show earlier messages to a newcomer admits nobody: the
 * newcomer reads from their join on, or not at all.
 *
 * <p>The participant row is written only once the colleague is in the Matrix room, and under the
 * same lock that checked for it, so a failed join leaves no row and a repeat adds nobody twice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InternalChatInvitationFacade {

  /**
   * The one history visibility that hides everything said before someone joined. "invited" would
   * already show messages from the invitation on, so it does not count.
   */
  static final String HIDDEN_HISTORY = "joined";

  private final @NonNull InternalSubsetChatFacade internalSubsetChatFacade;
  private final @NonNull ChatRepository chatRepository;
  private final @NonNull GroupChatParticipantRepository participantRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull MemberSetLock memberSetLock;
  private final @NonNull ChatAgencyRepository chatAgencyRepository;
  private final @NonNull PlatformTransactionManager transactions;
  private final @NonNull GroupChatMembershipService groupChatMembershipService;

  /** What the read phase found: the room, who invites and the row to write after the join. */
  private record Admission(
      String matrixRoomId,
      String ownerMatrixId,
      String colleagueId,
      String colleagueMatrixId,
      GroupChatParticipant row) {}

  /** The colleagues the caller can bring into the chat: the room's scope minus who is in. */
  public List<InternalChatRecipientDTO> recipients(Consultant caller, Long chatId) {
    return readOnly()
        .execute(
            status -> {
              var chat = requireInvitableRoom(caller, chatId);
              // A room that would refuse every invitation offers nobody to pick.
              requireHiddenHistory(chat.getMatrixRoomId());
              var inside = participantIds(chat);
              return internalSubsetChatFacade.recipients(ownerOf(chat), agencyIdOf(chat)).stream()
                  .filter(recipient -> !inside.contains(recipient.getConsultantId()))
                  .toList();
            });
  }

  /**
   * Invites and joins the colleague into the chat's room and records them as participant.
   *
   * @return {@code true} when the colleague was added, {@code false} when they already were in
   */
  public boolean invite(Consultant caller, Long chatId, String consultantId) {
    if (consultantId == null || consultantId.isBlank()) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.INTERNAL_CHAT_RECIPIENT_UNKNOWN);
    }
    try (var ignored = memberSetLock.acquire(List.of("invite:" + chatId, consultantId))) {
      var admission = readOnly().execute(status -> admit(caller, chatId, consultantId));
      if (admission == null) {
        // A repeated invitation changes nothing and never duplicates the participant.
        return false;
      }
      requireHiddenHistory(admission.matrixRoomId());
      var colleagueToken = joinRoom(admission);
      try {
        // Saved while the lock is held: a repeat finds the row and adds nobody twice.
        participantRepository.save(admission.row());
      } catch (RuntimeException failure) {
        compensateJoin(admission, colleagueToken, failure);
      }
      return true;
    }
  }

  /** All checks that read the database; {@code null} when the colleague is in already. */
  private Admission admit(Consultant caller, Long chatId, String consultantId) {
    var chat = requireInvitableRoom(caller, chatId);
    if (participantRepository
        .findBySeriesIdAndConsultantId(chat.getId(), consultantId)
        .isPresent()) {
      return null;
    }
    var owner = ownerOf(chat);
    var agencyId = agencyIdOf(chat);
    // Checked again on submission: a switch turned off after the list was opened still refuses.
    internalSubsetChatFacade.requireFeatureEnabled(owner, agencyId);
    var colleague =
        TenantContext.supplyAcrossTenants(
                () -> consultantRepository.findByIdAndDeleteDateIsNull(consultantId))
            .orElse(null);
    // An unknown id answers exactly like one outside the scope.
    if (colleague == null
        || !internalSubsetChatFacade.permitsRecipient(owner, colleague, agencyId)) {
      log.info("An invited colleague is outside the recipient scope of chat {}", chat.getId());
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.INTERNAL_CHAT_RECIPIENT_OUT_OF_SCOPE, HttpStatus.FORBIDDEN);
    }
    if (!MatrixIds.isUserId(colleague.getMatrixUserId())
        || !MatrixIds.isUserId(owner.getMatrixUserId())) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.INTERNAL_CHAT_RECIPIENT_UNKNOWN);
    }
    return new Admission(
        chat.getMatrixRoomId(),
        owner.getMatrixUserId(),
        colleague.getId(),
        colleague.getMatrixUserId(),
        participantRow(chat, owner, colleague));
  }

  private TransactionTemplate readOnly() {
    var template = new TransactionTemplate(transactions);
    template.setReadOnly(true);
    return template;
  }

  private Chat requireInvitableRoom(Consultant caller, Long chatId) {
    internalSubsetChatFacade.requireEncryption();
    var chat =
        TenantContext.supplyAcrossTenants(() -> chatRepository.findById(chatId))
            .orElseThrow(() -> new NotFoundException("Chat not found"));
    // Membership first: a stranger learns nothing about the chat, not even its kind.
    if (caller == null
        || participantRepository
            .findBySeriesIdAndConsultantId(chat.getId(), caller.getId())
            .isEmpty()) {
      throw new ForbiddenException("Only a participant of the chat can invite colleagues");
    }
    if (!chat.isMembersOnly()
        || !chat.isActive()
        || ChatConverter.conversationTypeOf(chat) != ConversationType.INTERNAL_GROUP
        || !MatrixIds.isRoomId(chat.getMatrixRoomId())) {
      throw new ConflictException(
          "Only an active members-only internal chat takes new colleagues",
          HttpStatusExceptionReason.INTERNAL_CHAT_NOT_INVITABLE);
    }
    return chat;
  }

  private void requireHiddenHistory(String matrixRoomId) {
    var visibility = matrixSynapseService.getRoomHistoryVisibility(matrixRoomId);
    if (visibility.isEmpty()) {
      // Unknown is not "hidden": ask the caller to try again once Matrix answers.
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.INTERNAL_CHAT_BUSY, HttpStatus.SERVICE_UNAVAILABLE);
    }
    if (!HIDDEN_HISTORY.equals(visibility.get())) {
      throw new ConflictException(
          "The room shows earlier messages to people who join later",
          HttpStatusExceptionReason.INTERNAL_CHAT_HISTORY_SHARED);
    }
  }

  /**
   * The owner invites, because only the owner holds the invite power level of a members-only room;
   * the colleague then joins with their own account, exactly as at creation. After an ownership
   * transfer the current owner may still be an ordinary member in Matrix, so their power is
   * repaired first, as for a re-created group room.
   *
   * @return the colleague's access token, needed to undo the join
   */
  private String joinRoom(Admission admission) {
    try {
      if (!groupChatMembershipService.ensureOwnerCanInvite(
          admission.matrixRoomId(), admission.ownerMatrixId())) {
        throw new IllegalStateException("The owner holds no power to invite");
      }
      var ownerToken = matrixSynapseService.loginAsUserAccessToken(admission.ownerMatrixId());
      if (ownerToken != null) {
        matrixSynapseService.inviteUserToRoom(
            admission.matrixRoomId(), admission.colleagueMatrixId(), ownerToken);
        var colleagueToken =
            matrixSynapseService.loginAsUserAccessToken(admission.colleagueMatrixId());
        if (colleagueToken != null
            && matrixSynapseService.joinRoom(admission.matrixRoomId(), colleagueToken)) {
          return colleagueToken;
        }
      }
    } catch (Exception failure) {
      log.warn(
          "Colleague {} could not join a members-only room: {}",
          admission.colleagueId(),
          failure.getClass().getSimpleName());
    }
    throw new CustomValidationHttpStatusException(
        HttpStatusExceptionReason.INTERNAL_CHAT_MEMBERSHIP_FAILED, HttpStatus.BAD_GATEWAY);
  }

  /**
   * The participant row could not be written after the join: take the colleague out of the room
   * again, so nobody reads a private chat without being recorded in it. A failed undo is reported
   * as such, never as "nothing happened".
   */
  private void compensateJoin(
      Admission admission, String colleagueToken, RuntimeException failure) {
    boolean left;
    try {
      left = matrixSynapseService.leaveRoom(admission.matrixRoomId(), colleagueToken);
    } catch (RuntimeException leaveFailure) {
      failure.addSuppressed(leaveFailure);
      left = false;
    }
    if (!left) {
      log.error(
          "Colleague {} joined members-only chat room {} but could neither be recorded nor removed"
              + " again; an operator must remove the membership",
          admission.colleagueId(),
          admission.matrixRoomId());
      throw new InternalServerErrorException(
          "The colleague joined the room but could not be recorded or removed again");
    }
    log.warn(
        "Colleague {} was removed from the room again after the participant record failed: {}",
        admission.colleagueId(),
        failure.getClass().getSimpleName());
    throw new CustomValidationHttpStatusException(
        HttpStatusExceptionReason.INTERNAL_CHAT_MEMBERSHIP_FAILED, HttpStatus.BAD_GATEWAY);
  }

  /** Same shape as the rows written at creation: technical session id plus the chat as series. */
  private GroupChatParticipant participantRow(Chat chat, Consultant owner, Consultant colleague) {
    var sessionId =
        participantRepository
            .findBySeriesIdAndConsultantId(chat.getId(), owner.getId())
            .map(GroupChatParticipant::getChatId)
            .orElseThrow(
                () ->
                    new ConflictException(
                        "The owner is no longer a participant of the chat",
                        HttpStatusExceptionReason.INTERNAL_CHAT_NOT_INVITABLE));
    var row = new GroupChatParticipant();
    row.setChatId(sessionId);
    row.setSeriesId(chat.getId());
    row.setRole(ParticipantRole.CO_MODERATOR);
    row.setConsultantId(colleague.getId());
    return row;
  }

  private Set<String> participantIds(Chat chat) {
    return participantRepository.findBySeriesId(chat.getId()).stream()
        .map(GroupChatParticipant::getConsultantId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  private static Consultant ownerOf(Chat chat) {
    if (chat.getChatOwner() == null) {
      throw new ConflictException(
          "The chat has no owner", HttpStatusExceptionReason.INTERNAL_CHAT_NOT_INVITABLE);
    }
    return chat.getChatOwner();
  }

  /** The counselling centre the chat was created for. */
  private Long agencyIdOf(Chat chat) {
    return TenantContext.supplyAcrossTenants(() -> chatAgencyRepository.findByChat_Id(chat.getId()))
        .stream()
        .map(ChatAgency::getAgencyId)
        .filter(Objects::nonNull)
        .min(Comparator.naturalOrder())
        .orElseThrow(
            () ->
                new ConflictException(
                    "The chat has no counselling centre",
                    HttpStatusExceptionReason.INTERNAL_CHAT_NOT_INVITABLE));
  }
}
